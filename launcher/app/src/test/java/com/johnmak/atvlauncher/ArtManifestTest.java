package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class ArtManifestTest {
    private static final String SHA_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA_B = "fedcba9876543210fedcba9876543210fedcba98";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String entry(long id, String file, String sha1) {
        return "{\"id\":" + id + ",\"title\":\"T" + id + "\",\"year\":\"1891\","
                + "\"categories\":[\"era:19c\"],\"file\":\"" + file + "\",\"sha1\":\"" + sha1 + "\"}";
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
}
