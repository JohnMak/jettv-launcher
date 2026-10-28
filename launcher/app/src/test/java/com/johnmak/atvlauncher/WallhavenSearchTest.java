package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WallhavenSearchTest {
    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** The raw bytes of a resource fixture (the project's {@code *.json} convention, as {@code WeatherModelTest}). */
    private static byte[] fixtureBytes(String name) throws Exception {
        try (InputStream in = WallhavenSearchTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return in.readAllBytes();
        }
    }

    private static String entry(String id, String path, String resolution) {
        return "{\"id\":\"" + id + "\",\"path\":\"" + path + "\",\"resolution\":\"" + resolution + "\"}";
    }

    private static String cdn(String id) {
        return "https://w.wallhaven.cc/full/94/wallhaven-" + id + ".jpg";
    }

    private static String response(String meta, String... entries) {
        return "{\"data\":[" + String.join(",", entries) + "],\"meta\":" + meta + "}";
    }

    private static final String META = "{\"current_page\":1,\"last_page\":100,\"per_page\":24,\"seed\":\"aBc123\"}";

    @Test public void parsesGoodResponse() {
        String json = response(META, entry("94x38z", cdn("94x38z"), "3840x2160"), entry("7dkz9q", cdn("7dkz9q"), "5120x2880"));
        FetchResult<WallhavenSearch.Page> r = WallhavenSearch.parse(bytes(json));
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        WallhavenSearch.Page p = r.value();
        assertEquals(2, p.images.size());
        assertEquals("94x38z", p.images.get(0).id);
        assertEquals(cdn("94x38z"), p.images.get(0).path);
        assertEquals("3840x2160", p.images.get(0).resolution);
        assertEquals("aBc123", p.seed);
        assertEquals(1, p.page);
        assertEquals(100, p.lastPage);
    }

    @Test public void emptyDataIsAValidEmptyPageNotAnError() {
        FetchResult<WallhavenSearch.Page> r = WallhavenSearch.parse(bytes(response(META)));
        assertTrue(r.isOk());
        assertEquals("a parsed page with zero usable descriptors is valid-empty", 0, r.value().images.size());
        assertEquals(100, r.value().lastPage);
    }

    @Test public void missingDataArrayIsAnError() {
        assertFalse(WallhavenSearch.parse(bytes("{\"meta\":" + META + "}")).isOk());
    }

    @Test public void rejectsNonJsonNullAndOverByteCap() {
        assertFalse(WallhavenSearch.parse(bytes("not json")).isOk());
        assertFalse(WallhavenSearch.parse(null).isOk());
        byte[] tooBig = new byte[(int) WallhavenSearch.JSON_MAX_BYTES + 1];
        assertFalse(WallhavenSearch.parse(tooBig).isOk());
    }

    @Test public void rejectsTooManyEntries() {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i <= WallhavenSearch.MAX_ENTRIES; i++) {
            if (i > 0) entries.append(',');
            entries.append(entry("id" + i, cdn("id" + i), "3840x2160"));
        }
        assertFalse(WallhavenSearch.parse(bytes("{\"data\":[" + entries + "],\"meta\":" + META + "}")).isOk());
    }

    @Test public void skipsMalformedAndOffAllowlistEntriesButKeepsTheRest() {
        String offAllowlist = entry("bad1", "https://th.wallhaven.cc/small/x.jpg", "3840x2160"); // wrong host
        String httpEntry = entry("bad2", "http://w.wallhaven.cc/full/x.jpg", "3840x2160");        // not https
        String noId = "{\"path\":\"" + cdn("x") + "\",\"resolution\":\"3840x2160\"}";
        String good = entry("good", cdn("good"), "3840x2160");
        FetchResult<WallhavenSearch.Page> r = WallhavenSearch.parse(
                bytes(response(META, offAllowlist, httpEntry, noId, good)));
        assertTrue(r.isOk());
        assertEquals("only the one well-formed, on-allowlist descriptor survives", 1, r.value().images.size());
        assertEquals("good", r.value().images.get(0).id);
    }

    @Test public void skipsEntriesWithOverlongStrings() {
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i <= WallhavenSearch.MAX_ID_LEN; i++) longId.append('a');
        String overId = entry(longId.toString(), cdn("x"), "3840x2160");
        FetchResult<WallhavenSearch.Page> r = WallhavenSearch.parse(bytes(response(META, overId)));
        assertTrue(r.isOk());
        assertEquals(0, r.value().images.size());
    }

    @Test public void clampsPaginationSanity() {
        String weird = "{\"current_page\":-5,\"last_page\":999999999,\"seed\":\"s\"}";
        FetchResult<WallhavenSearch.Page> r = WallhavenSearch.parse(bytes(response(weird, entry("a", cdn("a"), "3840x2160"))));
        assertTrue(r.isOk());
        assertEquals("negative page coerced to 0", 0, r.value().page);
        assertEquals("absurd last_page clamped to the sanity ceiling", WallhavenSearch.MAX_PAGE, r.value().lastPage);
    }

    @Test public void missingMetaYieldsEmptySeedAndZeroPages() {
        FetchResult<WallhavenSearch.Page> r = WallhavenSearch.parse(
                bytes("{\"data\":[" + entry("a", cdn("a"), "3840x2160") + "]}"));
        assertTrue(r.isOk());
        assertEquals("", r.value().seed);
        assertEquals(0, r.value().page);
        assertEquals(0, r.value().lastPage);
    }

    // ---- integration: the adapter parses a realistic, full-fidelity sample response -----------

    /**
     * Integration against a REAL Wallhaven {@code /api/v1/search} sample fixture (not a hand-trimmed inline string):
     * every production field is present ({@code url}, {@code views}, {@code purity}, {@code dimension_x/y},
     * {@code file_type}, {@code colors}, a {@code thumbs} object on {@code th.wallhaven.cc}, …). The adapter must
     * extract ONLY {@code id}/{@code path}/{@code resolution}, ignore all the noise, and NEVER let a thumbnail
     * ({@code th.wallhaven.cc}) field leak into a usable {@code path} — only the full-res {@code w.wallhaven.cc}
     * {@code path} survives, and it stays on the two-host allowlist.
     */
    @Test public void parsesTheRealisticSampleSearchFixture() throws Exception {
        FetchResult<WallhavenSearch.Page> r = WallhavenSearch.parse(fixtureBytes("wallhaven/search_page.json"));
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        WallhavenSearch.Page p = r.value();

        assertEquals("all three real descriptors are extracted", 3, p.images.size());
        assertEquals("94x38z", p.images.get(0).id);
        assertEquals("https://w.wallhaven.cc/full/94/wallhaven-94x38z.jpg", p.images.get(0).path);
        assertEquals("3840x2160", p.images.get(0).resolution);
        assertEquals("the full-res PNG path, not a thumbnail", "https://w.wallhaven.cc/full/7d/wallhaven-7dkz9q.png",
                p.images.get(1).path);
        assertEquals("5120x2880", p.images.get(1).resolution);
        assertEquals("eo8k2l", p.images.get(2).id);
        assertEquals("7680x4320", p.images.get(2).resolution);

        assertEquals("meta.seed is carried for random-pagination continuity", "aBc123", p.seed);
        assertEquals(1, p.page);
        assertEquals(100, p.lastPage);

        for (WallhavenImage img : p.images) {
            assertTrue("every surviving path stays on the two-host allowlist (full-res CDN, https)",
                    WallhavenHosts.check(img.path).isOk());
            assertFalse("a thumbnail host must never survive as a usable path", img.path.contains("th.wallhaven.cc"));
        }
    }
}
