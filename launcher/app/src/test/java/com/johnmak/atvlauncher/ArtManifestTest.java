package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ArtManifestTest {
    private static final String SHA_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA_B = "fedcba9876543210fedcba9876543210fedcba98";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String entry(long id, String file, String sha1) {
        return entry(id, file, sha1, "era:19c");
    }

    private static String entry(long id, String file, String sha1, String category) {
        return "{\"id\":" + id + ",\"title\":\"T" + id + "\",\"year\":\"1891\","
                + "\"categories\":[\"" + category + "\"],\"file\":\"" + file + "\",\"sha1\":\"" + sha1 + "\"}";
    }

    private static String fileFor(long id, String sha1) {
        return id + "-" + sha1.substring(0, 12) + ".jpg";
    }

    private static String manifest(String baseUrl, String... entries) {
        return "{\"base_url\":\"" + baseUrl + "\",\"generated_at\":\"2026-10-26T00:00:00Z\",\"entries\":["
                + String.join(",", entries) + "]}";
    }

    private static final String GOOD = manifest(ArtHosts.ART_BASE_URL,
            entry(193223543L, "193223543-" + SHA_A.substring(0, 12) + ".jpg", SHA_A),
            entry(56421854L, "56421854-" + SHA_B.substring(0, 12) + ".jpg", SHA_B));

    @Test public void parsesGoodManifest() {
        FetchResult<ArtManifest> r = ArtManifest.parse(bytes(GOOD));
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertEquals(2, r.value().entries().size());
        ArtManifest.Entry e = r.value().entries().get(0);
        assertEquals(193223543L, e.id);
        assertEquals("T193223543", e.title);
        assertEquals("1891", e.year);
        assertEquals(SHA_A, e.sha1);
        assertEquals("era:19c", e.categories.get(0));
        assertTrue(ArtManifest.isValidCandidate(bytes(GOOD)));
    }

    @Test public void rejectsTruncatedJson() {
        assertFalse(ArtManifest.isValidCandidate(bytes("{\"base_url\":\"" + ArtHosts.ART_BASE_URL + "\",\"entries\":[")));
    }

    @Test public void rejectsHtmlErrorPage() {
        assertFalse(ArtManifest.isValidCandidate(bytes("<html><body>502 Bad Gateway</body></html>")));
    }

    @Test public void rejectsWrongBaseUrl() {
        assertFalse(ArtManifest.isValidCandidate(bytes(manifest("https://evil.com/",
                entry(1L, "1-" + SHA_A.substring(0, 12) + ".jpg", SHA_A)))));
        assertFalse(ArtManifest.isValidCandidate(bytes(manifest("https://art.jmwork.ru",   // missing trailing slash
                entry(1L, "1-" + SHA_A.substring(0, 12) + ".jpg", SHA_A)))));
    }

    @Test public void rejectsZeroValidEntries() {
        assertFalse(ArtManifest.isValidCandidate(bytes(manifest(ArtHosts.ART_BASE_URL))));
        // one entry, but every field makes it malformed → skipped → zero valid → reject
        assertFalse(ArtManifest.isValidCandidate(bytes(manifest(ArtHosts.ART_BASE_URL,
                entry(0L, "x.jpg", SHA_A)))));                       // id not positive
        assertFalse(ArtManifest.isValidCandidate(bytes(manifest(ArtHosts.ART_BASE_URL,
                entry(1L, "../x.jpg", SHA_A)))));                    // unsafe file
        assertFalse(ArtManifest.isValidCandidate(bytes(manifest(ArtHosts.ART_BASE_URL,
                entry(1L, "x.jpg", "NOTHEX")))));                    // bad sha1
    }

    @Test public void skipsMalformedEntriesButKeepsValidOnes() {
        String m = manifest(ArtHosts.ART_BASE_URL,
                entry(1L, "../evil.jpg", SHA_A),                      // dropped (unsafe)
                "{\"id\":2}",                                          // dropped (no file/sha1)
                entry(3L, "3-" + SHA_B.substring(0, 12) + ".jpg", SHA_B)); // kept
        FetchResult<ArtManifest> r = ArtManifest.parse(bytes(m));
        assertTrue(r.isOk());
        assertEquals(1, r.value().entries().size());
        assertEquals(3L, r.value().entries().get(0).id);
    }

    @Test public void rejectsOverCapAndTooManyEntries() {
        byte[] big = new byte[(int) ArtManifest.MANIFEST_MAX_BYTES + 1];
        assertFalse(ArtManifest.isValidCandidate(big));

        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= ArtManifest.MAX_MANIFEST_ENTRIES + 1; i++) {
            if (i > 1) many.append(',');
            many.append(entry(i, i + "-" + SHA_A.substring(0, 12) + ".jpg", SHA_A));
        }
        assertFalse(ArtManifest.isValidCandidate(
                bytes(manifest(ArtHosts.ART_BASE_URL, many.toString()))));
    }

    @Test public void rejectsNullAndNonObject() {
        assertFalse(ArtManifest.isValidCandidate(null));
        assertFalse(ArtManifest.isValidCandidate(bytes("[1,2,3]")));
        assertFalse(ArtManifest.isValidCandidate(bytes("")));
    }

    // ---- Step 5: availablePresets / shuffledQueue -------------------------------------------------

    /** Builds {@code count} entries all tagged {@code category}, ids {@code startId.. startId+count-1}. */
    private static String[] entriesOf(int count, long startId, String category) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            long id = startId + i;
            String sha1 = String.format("%040x", id); // distinct well-formed hex sha1 per id
            out[i] = entry(id, fileFor(id, sha1), sha1, category);
        }
        return out;
    }

    @Test public void availablePresetsIncludesOnlyAllBelowThreshold() throws Exception {
        // GOOD has 2 entries, both era:19c — below MIN_PRESET_WORKS(4), so only ALL is offered.
        ArtManifest m = ArtManifest.parse(bytes(GOOD)).value();
        assertEquals(Collections.singletonList(ArtPreset.ALL), m.availablePresets());
    }

    @Test public void availablePresetsOffersPresetAtExactThreshold() throws Exception {
        String m = manifest(ArtHosts.ART_BASE_URL, entriesOf(ArtPreset.MIN_PRESET_WORKS, 1, "movement:peredvizhniki"));
        ArtManifest manifest = ArtManifest.parse(bytes(m)).value();
        assertEquals(java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.PEREDVIZHNIKI), manifest.availablePresets());
    }

    @Test public void availablePresetsHidesPresetOneBelowThreshold() throws Exception {
        String m = manifest(ArtHosts.ART_BASE_URL,
                entriesOf(ArtPreset.MIN_PRESET_WORKS - 1, 1, "movement:peredvizhniki"));
        ArtManifest manifest = ArtManifest.parse(bytes(m)).value();
        assertEquals(Collections.singletonList(ArtPreset.ALL), manifest.availablePresets());
    }

    @Test public void availablePresetsOrderIsCatalogOrder() throws Exception {
        List<String> entries = new ArrayList<>();
        Collections.addAll(entries, entriesOf(ArtPreset.MIN_PRESET_WORKS, 1, "movement:peredvizhniki"));
        Collections.addAll(entries, entriesOf(ArtPreset.MIN_PRESET_WORKS, 100, "era:19c"));
        Collections.addAll(entries, entriesOf(ArtPreset.MIN_PRESET_WORKS, 200, "movement:avantgarde"));
        ArtManifest manifest = ArtManifest.parse(bytes(manifest(ArtHosts.ART_BASE_URL,
                entries.toArray(new String[0])))).value();
        assertEquals(java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.PEREDVIZHNIKI, ArtPreset.ERA_19C,
                ArtPreset.AVANTGARDE), manifest.availablePresets());
    }

    @Test public void shuffledQueueFiltersByPresetAndStaysDeterministic() throws Exception {
        List<String> entries = new ArrayList<>();
        Collections.addAll(entries, entriesOf(5, 1, "movement:peredvizhniki"));
        Collections.addAll(entries, entriesOf(5, 100, "era:19c"));
        ArtManifest manifest = ArtManifest.parse(bytes(manifest(ArtHosts.ART_BASE_URL,
                entries.toArray(new String[0])))).value();

        List<ArtManifest.Entry> a = manifest.shuffledQueue(ArtPreset.PEREDVIZHNIKI, 42L);
        List<ArtManifest.Entry> b = manifest.shuffledQueue(ArtPreset.PEREDVIZHNIKI, 42L);
        assertEquals(5, a.size());
        assertEquals(idsOf(a), idsOf(b)); // same seed -> same order, every time

        Set<Long> ids = idsOf(a);
        for (long id = 1; id <= 5; id++) assertTrue(ids.contains(id)); // only the matching subset
        for (long id = 100; id <= 104; id++) assertFalse(ids.contains(id));
    }

    @Test public void shuffledQueueEmptyWhenPresetHasNoMatches() throws Exception {
        ArtManifest m = ArtManifest.parse(bytes(GOOD)).value(); // only era:19c entries
        assertTrue(m.shuffledQueue(ArtPreset.AVANTGARDE, 1L).isEmpty());
    }

    @Test public void shuffledQueueAllMatchesEveryEntry() throws Exception {
        ArtManifest m = ArtManifest.parse(bytes(GOOD)).value();
        assertEquals(2, m.shuffledQueue(ArtPreset.ALL, 7L).size());
    }

    private static Set<Long> idsOf(List<ArtManifest.Entry> entries) {
        Set<Long> ids = new HashSet<>();
        for (ArtManifest.Entry e : entries) ids.add(e.id);
        return ids;
    }
}
