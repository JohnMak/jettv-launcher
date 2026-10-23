package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class KinopoiskCacheTest {
    private static final long T0 = 1_000_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;
    private static final KinopoiskInfo HIT = new KinopoiskInfo("7.9", "8.1");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void hitsLiveSevenDaysMissesOneDay() {
        assertTrue(KinopoiskCache.fresh(HIT, T0, T0 + 167 * HOUR));
        assertFalse(KinopoiskCache.fresh(HIT, T0, T0 + 168 * HOUR));
        assertTrue(KinopoiskCache.fresh(KinopoiskInfo.NONE, T0, T0 + 23 * HOUR));
        assertFalse(KinopoiskCache.fresh(KinopoiskInfo.NONE, T0, T0 + 24 * HOUR));
        assertFalse("clock set back", KinopoiskCache.fresh(HIT, T0, T0 - 1));
    }

    @Test
    public void getReturnsOnlyFreshEntries() throws IOException {
        KinopoiskCache cache = KinopoiskCache.load(new File(tmp.getRoot(), "c.json")).value();
        assertNull(cache.get("k", T0));
        cache.put("k", HIT, T0);
        cache.put("miss", KinopoiskInfo.NONE, T0);
        assertEquals("8.1", cache.get("k", T0 + HOUR).imdb);
        assertNotNull(cache.get("miss", T0 + HOUR));
        assertNull(cache.get("miss", T0 + 25 * HOUR));
        assertNotNull(cache.get("k", T0 + 25 * HOUR));
    }

    @Test
    public void saveAndLoadRoundTrip() {
        File file = new File(tmp.getRoot(), "c.json");
        KinopoiskCache cache = KinopoiskCache.load(file).value();
        cache.put("a", HIT, T0);
        cache.put("b", KinopoiskInfo.NONE, T0);
        assertTrue(cache.save().isOk());
        assertFalse(new File(tmp.getRoot(), "c.json.tmp").exists());
        KinopoiskCache again = KinopoiskCache.load(file).value();
        assertEquals(2, again.size());
        KinopoiskInfo a = again.get("a", T0 + HOUR);
        assertEquals("7.9", a.kp);
        assertEquals("8.1", a.imdb);
        assertTrue(again.get("b", T0 + HOUR).isEmpty());
    }

    @Test
    public void fileOfAnotherVersionIsDiscarded() throws java.io.IOException {
        File file = new File(tmp.getRoot(), "old.json");
        java.nio.file.Files.write(file.toPath(),
                "{\"k\":{\"t\":1,\"kp\":\"7.9\",\"imdb\":\"8.1\",\"d\":\"x\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, KinopoiskCache.load(file).value().size());
    }

    @Test
    public void saveKeepsTheNewestEntries() {
        File file = new File(tmp.getRoot(), "c.json");
        KinopoiskCache cache = KinopoiskCache.load(file).value();
        for (int i = 0; i < KinopoiskCache.MAX_ENTRIES + 5; i++) cache.put("k" + i, HIT, T0 + i);
        assertTrue(cache.save().isOk());
        KinopoiskCache again = KinopoiskCache.load(file).value();
        assertEquals(KinopoiskCache.MAX_ENTRIES, again.size());
        assertNull(again.get("k0", T0 + 1000));
        assertNotNull(again.get("k" + (KinopoiskCache.MAX_ENTRIES + 4), T0 + 1000));
    }

    @Test
    public void corruptFileIsAnError() throws IOException {
        File file = tmp.newFile("bad.json");
        Files.write(file.toPath(), "{not json".getBytes("UTF-8"));
        assertFalse(KinopoiskCache.load(file).isOk());
        assertEquals(0, KinopoiskCache.empty(file).size());
    }
}
