package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class WallhavenSearchTest {
    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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
}
