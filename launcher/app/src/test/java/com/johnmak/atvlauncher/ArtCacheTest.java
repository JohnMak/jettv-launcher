package com.johnmak.atvlauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArtCacheTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static Set<String> pins(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private File images() {
        return new File(new File(tmp.getRoot(), "art-cache"), "images");
    }

    // ---- pure reserve-space decision table ----------------------------------------------------

    @Test public void reserveDecisionTable() {
        long need = ArtCache.IMAGE_MAX_BYTES;
        long reserve = ArtCache.RESERVE_FREE_BYTES;
        // ample free space → write directly
        assertEquals(ArtCache.Reserve.WRITE, ArtCache.decide(need + reserve, 0, need, reserve));
        // short on free space but eviction reclaims enough → evict then write
        assertEquals(ArtCache.Reserve.EVICT_THEN_WRITE, ArtCache.decide(0, need + reserve, need, reserve));
        assertEquals(ArtCache.Reserve.EVICT_THEN_WRITE, ArtCache.decide(need, reserve, need, reserve));
        // neither free space nor eviction can satisfy it → unavailable
        assertEquals(ArtCache.Reserve.UNAVAILABLE, ArtCache.decide(0, 0, need, reserve));
        assertEquals(ArtCache.Reserve.UNAVAILABLE, ArtCache.decide(need, 0, need, reserve));
    }

    // ---- pure LRU eviction plan ---------------------------------------------------------------

    @Test public void evictsOldestFirstUntilUnderCap() {
        List<ArtCache.Stat> stats = Arrays.asList(
                new ArtCache.Stat("new.jpg", 300, 40),
                new ArtCache.Stat("old.jpg", 100, 40),
                new ArtCache.Stat("mid.jpg", 200, 40));
        // total 120, cap 50 → must drop oldest two (old, mid), keep newest
        assertEquals(Arrays.asList("old.jpg", "mid.jpg"),
                ArtCache.evictionPlan(stats, Collections.emptySet(), 50));
    }

    @Test public void underCapEvictsNothing() {
        List<ArtCache.Stat> stats = Collections.singletonList(new ArtCache.Stat("a.jpg", 1, 10));
        assertTrue(ArtCache.evictionPlan(stats, Collections.emptySet(), 100).isEmpty());
    }

    @Test public void pinnedAndPartNeverEvicted() {
        List<ArtCache.Stat> stats = Arrays.asList(
                new ArtCache.Stat("pinned.jpg", 1, 40),         // oldest but pinned
                new ArtCache.Stat("busy.jpg.part", 2, 40),       // in-flight temp
                new ArtCache.Stat("free.jpg", 3, 40));
        // total 120, cap 10 → only free.jpg may go; pinned + .part stay even though still over cap
        assertEquals(Collections.singletonList("free.jpg"),
                ArtCache.evictionPlan(stats, pins("pinned.jpg"), 10));
    }

    @Test public void capAccountingIncludesPartFiles() {
        // one completed 40 + one .part 40 = 80 over a 60 cap; only the completed file is evictable
        List<ArtCache.Stat> stats = Arrays.asList(
                new ArtCache.Stat("a.jpg", 1, 40),
                new ArtCache.Stat("b.jpg.part", 2, 40));
        assertEquals(Collections.singletonList("a.jpg"),
                ArtCache.evictionPlan(stats, Collections.emptySet(), 60));
    }

    // ---- pure deficit-based eviction (free-space pressure) ------------------------------------

    @Test public void evictForReclaimsDeficitOldestFirst() {
        List<ArtCache.Stat> stats = Arrays.asList(
                new ArtCache.Stat("new.jpg", 300, 40),
                new ArtCache.Stat("old.jpg", 100, 40),
                new ArtCache.Stat("mid.jpg", 200, 40));
        assertEquals(Arrays.asList("old.jpg", "mid.jpg"), ArtCache.evictFor(stats, Collections.emptySet(), 50));
        assertEquals(Collections.singletonList("old.jpg"), ArtCache.evictFor(stats, Collections.emptySet(), 30));
        assertTrue(ArtCache.evictFor(stats, Collections.emptySet(), 0).isEmpty());
        // pinned skipped even under pressure
        assertEquals(Collections.singletonList("mid.jpg"), ArtCache.evictFor(stats, pins("old.jpg"), 30));
    }

    // ---- reference-counted pins (regression: P2 ArtCache pins) --------------------------------

    @Test public void pinsAreReferenceCounted() {
        ArtCache cache = new ArtCache(tmp.getRoot());
        cache.pin("a.jpg");
        cache.pin("a.jpg");                       // two independent holders
        cache.unpin("a.jpg");
        assertTrue("still protected after one of two holders releases", cache.isPinned("a.jpg"));
        cache.unpin("a.jpg");
        assertFalse("protection ends only when the last holder releases", cache.isPinned("a.jpg"));
        cache.unpin("a.jpg");                     // underflow is a no-op
        assertFalse(cache.isPinned("a.jpg"));
    }

    // ---- reservation accounting under the lock (regression: P2 free-space reservation) --------

    @Test public void reservationAccountsForInFlightWritersUnderLock() {
        long roomForExactlyOne = ArtCache.IMAGE_MAX_BYTES + ArtCache.RESERVE_FREE_BYTES;
        ArtCache cache = new ArtCache(tmp.getRoot(), () -> roomForExactlyOne);
        assertTrue(cache.ensureImagesDir());
        assertTrue("first writer admitted", cache.reserveForImage());
        assertFalse("second writer must not be admitted against the same space", cache.reserveForImage());
        cache.releaseReservation();
        assertTrue("space frees up once the first write finishes", cache.reserveForImage());
    }

    // ---- unique temp names (regression: P1 shared .part) --------------------------------------

    @Test public void newPartProducesUniqueTemps() {
        ArtCache cache = new ArtCache(tmp.getRoot());
        File p1 = cache.newPart("x.jpg").value();
        File p2 = cache.newPart("x.jpg").value();
        assertNotEquals(p1, p2);
        assertTrue(p1.getName().endsWith(".part"));
        assertTrue(p2.getName().endsWith(".part"));
        assertTrue(p1.isFile() && p2.isFile());
    }

    // ---- integrity ----------------------------------------------------------------------------

    @Test public void hitVerifiesSha1AndCorruptIsEvicted() throws IOException {
        ArtCache cache = new ArtCache(tmp.getRoot());
        assertTrue(cache.ensureImagesDir());
        byte[] data = "painting-bytes".getBytes();
        String sha1 = ArtCache.sha1Hex(data);
        File good = new File(images(), "a.jpg");
        Files.write(good.toPath(), data);
        assertNotNull(cache.hit("a.jpg", sha1));                 // present + matches
        assertNull(cache.hit("a.jpg", "deadbeef" + sha1.substring(8))); // wrong expected hash
        assertFalse("corrupt file deleted on mismatch", good.isFile());
        assertNull(cache.hit("missing.jpg", sha1));              // absent → miss
    }

    // ---- real-filesystem writes ---------------------------------------------------------------

    @Test public void commitVerifiesThenInstallsAtomically() throws IOException {
        ArtCache cache = new ArtCache(tmp.getRoot());
        byte[] data = "the-image".getBytes();
        String sha1 = ArtCache.sha1Hex(data);
        File part = cache.newPart("x.jpg").value();
        Files.write(part.toPath(), data);
        FetchResult<File> r = cache.commit(part, "x.jpg", sha1);
        assertTrue(r.isOk());
        assertArrayEquals(data, Files.readAllBytes(r.value().toPath()));
        assertFalse(part.isFile());
    }

    @Test public void commitRejectsCorruptDownload() throws IOException {
        ArtCache cache = new ArtCache(tmp.getRoot());
        File part = cache.newPart("x.jpg").value();
        Files.write(part.toPath(), "wrong".getBytes());
        FetchResult<File> r = cache.commit(part, "x.jpg", ArtCache.sha1Hex("right".getBytes()));
        assertFalse(r.isOk());
        assertFalse("part cleaned up", part.isFile());
        assertFalse(new File(images(), "x.jpg").isFile());
    }

    @Test public void sweepRemovesStrayPartsInImagesAndRoot() throws IOException {
        ArtCache cache = new ArtCache(tmp.getRoot());
        assertTrue(cache.ensureImagesDir());
        File root = new File(tmp.getRoot(), "art-cache");
        File strayImage = new File(images(), "ghost.jpg.part");
        File strayManifest = new File(root, "manifest-123.part");
        File keep = new File(images(), "keep.jpg");
        Files.write(strayImage.toPath(), new byte[1]);
        Files.write(strayManifest.toPath(), new byte[1]);
        Files.write(keep.toPath(), new byte[1]);
        cache.sweepParts();
        assertFalse(strayImage.isFile());
        assertFalse(strayManifest.isFile());
        assertTrue(keep.isFile());
    }

    // ---- regression: atomic install never destroys last-valid (P1 atomicReplace fallback) -----

    @Test public void installFailureRetainsExistingDestination() throws IOException {
        ArtCache cache = new ArtCache(tmp.getRoot());
        assertTrue(cache.ensureImagesDir());
        File dst = new File(images(), "keep.jpg");
        byte[] lastValid = "last-valid-bytes".getBytes();
        Files.write(dst.toPath(), lastValid);
        File missingSrc = new File(images(), "ghost.part"); // does not exist → the move fails
        FetchResult<File> r = ArtCache.atomicReplace(missingSrc, dst);
        assertFalse("a failed install is an error", r.isOk());
        assertArrayEquals("the existing last-valid destination is retained", lastValid, Files.readAllBytes(dst.toPath()));
    }

    // ---- regression: corrupt-hit invalidation serialized with install (P2 hit vs commit) -------

    @Test public void corruptHitDoesNotDeleteConcurrentInstall() throws Exception {
        ArtCache cache = new ArtCache(tmp.getRoot());
        assertTrue(cache.ensureImagesDir());
        byte[] good = "the-correct-image".getBytes();
        String sha1 = ArtCache.sha1Hex(good);
        String file = "race.jpg";
        File dest = new File(images(), file);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 40; round++) {
                Files.write(dest.toPath(), "tampered".getBytes()); // each round starts corrupt
                File part = cache.newPart(file).value();
                Files.write(part.toPath(), good);
                CyclicBarrier gate = new CyclicBarrier(2);
                Future<?> hit = ex.submit(() -> { await(gate); return cache.hit(file, sha1); });
                Future<?> commit = ex.submit(() -> { await(gate); return cache.commit(part, file, sha1); });
                hit.get(5, TimeUnit.SECONDS);
                commit.get(5, TimeUnit.SECONDS);
                // whichever serial order the lock imposed, the good install survives intact
                assertTrue("good install survives corrupt-hit invalidation (round " + round + ")", dest.isFile());
                assertArrayEquals(good, Files.readAllBytes(dest.toPath()));
            }
        } finally {
            ex.shutdownNow();
        }
    }

    private static void await(CyclicBarrier gate) {
        try {
            gate.await(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test public void manifestRoundTripIsAtomicReplaceOutsideImages() throws IOException {
        ArtCache cache = new ArtCache(tmp.getRoot());
        assertFalse(cache.readManifest().isOk());                // none yet
        byte[] first = "{\"base_url\":\"first\"}".getBytes();
        assertTrue(cache.writeManifestAtomic(first).isOk());
        assertArrayEquals(first, cache.readManifest().value());
        // a second write fully replaces the first (atomic install, no partial content)
        byte[] second = "{\"base_url\":\"second-and-longer\"}".getBytes();
        assertTrue(cache.writeManifestAtomic(second).isOk());
        assertArrayEquals(second, cache.readManifest().value());
        File root = new File(tmp.getRoot(), "art-cache");
        assertTrue(new File(root, "manifest.json").isFile());
        // the unique temps are consumed by the atomic move — none left behind
        File[] leftovers = root.listFiles((d, n) -> n.endsWith(".part"));
        assertEquals(0, leftovers == null ? 0 : leftovers.length);
    }
}
