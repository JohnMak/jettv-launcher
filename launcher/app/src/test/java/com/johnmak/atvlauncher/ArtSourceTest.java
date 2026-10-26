package com.johnmak.atvlauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArtSourceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String FILE = "193223543-" + SHA.substring(0, 12) + ".jpg";

    private static byte[] goodManifest() {
        return ("{\"base_url\":\"" + ArtHosts.ART_BASE_URL + "\",\"entries\":[{"
                + "\"id\":1,\"title\":\"Morning\",\"year\":\"1889\",\"categories\":[\"era:19c\"],"
                + "\"file\":\"" + FILE + "\",\"sha1\":\"" + SHA + "\"}]}").getBytes();
    }

    // ---- fakes --------------------------------------------------------------------------------

    /** A scripted, thread-safe connection factory; records how many connections were opened. */
    private static final class Net implements ArtSource.Connections {
        final ConcurrentLinkedQueue<FakeConn> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger opens = new AtomicInteger();

        Net(FakeConn... conns) {
            Collections.addAll(queue, conns);
        }

        @Override public HttpURLConnection open(String url) throws IOException {
            opens.incrementAndGet();
            FakeConn c = queue.poll();
            if (c == null) throw new IOException("no scripted connection for " + url);
            c.openedUrl = url;
            return c;
        }
    }

    /** A controllable HttpURLConnection: scripted status/headers/body, optional blocking on headers or body. */
    private static final class FakeConn extends HttpURLConnection {
        int status = 200;
        String location;
        long contentLength = -1;
        byte[] body = new byte[0];
        boolean blockHeaders;
        boolean blockBody;
        Runnable onFirstRead;             // runs once at the start of the first body read, then EOF
        AtomicInteger liveGauge;          // concurrency probe: live connections (headers..disconnect)
        AtomicInteger maxGauge;
        long headerDwellMs;               // widen the live window so overlapping fetches are observable
        String openedUrl;
        volatile boolean disconnected;
        final CountDownLatch released = new CountDownLatch(1);
        final CountDownLatch readEntered = new CountDownLatch(1);

        FakeConn() throws IOException {
            super(new URL("https://art.jmwork.ru/placeholder"));
        }

        static FakeConn ok(byte[] body, long contentLength) throws IOException {
            FakeConn c = new FakeConn();
            c.status = 200;
            c.body = body;
            c.contentLength = contentLength;
            return c;
        }

        static FakeConn redirect(int code, String location) throws IOException {
            FakeConn c = new FakeConn();
            c.status = code;
            c.location = location;
            return c;
        }

        static FakeConn status(int code) throws IOException {
            FakeConn c = new FakeConn();
            c.status = code;
            return c;
        }

        private void await() {
            try {
                released.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override public int getResponseCode() throws IOException {
            if (liveGauge != null) {
                int now = liveGauge.incrementAndGet();
                maxGauge.updateAndGet(m -> Math.max(m, now));
            }
            if (headerDwellMs > 0) {
                try { Thread.sleep(headerDwellMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (blockHeaders) {
                await();
                throw new IOException("disconnected before headers");
            }
            return status;
        }

        @Override public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }

        @Override public long getContentLengthLong() {
            return contentLength;
        }

        @Override public InputStream getInputStream() {
            if (blockBody) {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        return readBlocked();
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        return readBlocked();
                    }
                };
            }
            if (onFirstRead != null) {
                return new InputStream() {
                    private boolean fired;
                    @Override public int read() {
                        return readHook();
                    }
                    @Override public int read(byte[] b, int off, int len) {
                        return readHook();
                    }
                    private int readHook() {
                        if (!fired) {
                            fired = true;
                            onFirstRead.run();
                        }
                        return -1; // EOF immediately after the hook
                    }
                };
            }
            return new ByteArrayInputStream(body);
        }

        private int readBlocked() throws IOException {
            readEntered.countDown();
            await();
            throw new IOException("disconnected mid-read");
        }

        @Override public void connect() { }

        @Override public void disconnect() {
            boolean first = !disconnected;
            disconnected = true;
            if (first && liveGauge != null) liveGauge.decrementAndGet();
            released.countDown();
        }

        @Override public boolean usingProxy() {
            return false;
        }
    }

    private static final ArtSource.Clock FIXED_CLOCK = () -> 0L;
    private static final ArtSource.Watchdog NOOP_WATCHDOG = (delay, onFire) -> () -> { };

    private static final class CapturingWatchdog implements ArtSource.Watchdog {
        private volatile Runnable onFire;
        private final CountDownLatch armed = new CountDownLatch(1);

        @Override public Arm arm(long delayMs, Runnable fire) {
            this.onFire = fire;
            armed.countDown();
            return () -> { };
        }

        void fireAfterArming() throws InterruptedException {
            assertTrue("watchdog was armed", armed.await(3, TimeUnit.SECONDS));
            onFire.run();
        }
    }

    private ArtSource source(Net net, ArtSource.Clock clock, ArtSource.Watchdog wd) {
        return new ArtSource(new ArtCache(tmp.getRoot()), "ua-test", net, clock, wd);
    }

    // ---- manifest: validate-before-replace (D9) ----------------------------------------------

    @Test public void refreshStoresValidCandidate() {
        FetchResult<ArtManifest> r = source(new Net(okConn(goodManifest())), FIXED_CLOCK, NOOP_WATCHDOG).manifest();
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertEquals(1, r.value().entries().size());
        assertTrue(new ArtCache(tmp.getRoot()).readManifest().isOk());
    }

    @Test public void badCandidatesKeepLastValidAcrossRestart() {
        // prime a valid last-valid manifest
        assertTrue(source(new Net(okConn(goodManifest())), FIXED_CLOCK, NOOP_WATCHDOG).manifest().isOk());

        byte[] oversize = new byte[(int) ArtManifest.MANIFEST_MAX_BYTES + 1];
        byte[][] bad = {
                "{\"base_url\":\"".getBytes(),                         // truncated JSON
                "<html>502</html>".getBytes(),                        // HTML error page
                ("{\"base_url\":\"" + ArtHosts.ART_BASE_URL + "\",\"entries\":[]}").getBytes(), // zero entries
                ("{\"base_url\":\"https://evil.com/\",\"entries\":[{\"id\":1,\"file\":\"" + FILE
                        + "\",\"sha1\":\"" + SHA + "\"}]}").getBytes(),  // wrong base_url
                oversize,                                              // over cap
        };
        for (byte[] candidate : bad) {
            // a FRESH source + cache on the same dir == a simulated process restart
            FakeConn c = okConn(candidate);
            c.contentLength = -1;
            FetchResult<ArtManifest> r = source(new Net(c), FIXED_CLOCK, NOOP_WATCHDOG).manifest();
            assertTrue("last-valid still served after bad candidate", r.isOk());
            assertEquals("Morning", r.value().entries().get(0).title);
        }
    }

    @Test public void bothUnreachableAndUncachedSignalsEmpty() {
        FetchResult<ArtManifest> r = source(new Net(statusConn(500)), FIXED_CLOCK, NOOP_WATCHDOG).manifest();
        assertFalse(r.isOk()); // 500, no cache → empty signal
    }

    // ---- guarded fetch: redirects / caps ------------------------------------------------------

    @Test public void offHostRedirectIsRefused() {
        Net net = new Net(redirectConn(302, "https://evil.com/manifest.json"));
        FetchResult<ArtManifest> r = source(net, FIXED_CLOCK, NOOP_WATCHDOG).manifest();
        assertFalse(r.isOk());
        assertFalse("no last-valid was written", new ArtCache(tmp.getRoot()).readManifest().isOk());
    }

    @Test public void sameHostRedirectIsFollowed() {
        Net net = new Net(redirectConn(301, "https://art.jmwork.ru/manifest.json"), okConn(goodManifest()));
        assertTrue(source(net, FIXED_CLOCK, NOOP_WATCHDOG).manifest().isOk());
        assertEquals(2, net.opens.get());
    }

    @Test public void contentLengthPrecheckRejectsOversized() {
        FakeConn c = okConn(new byte[10]);
        c.contentLength = ArtManifest.MANIFEST_MAX_BYTES + 1;
        FetchResult<ArtManifest> r = source(new Net(c), FIXED_CLOCK, NOOP_WATCHDOG).manifest();
        assertFalse(r.isOk());
    }

    @Test public void oversizedBodyAbortedMidStream() {
        FakeConn c = okConn(new byte[(int) ArtManifest.MANIFEST_MAX_BYTES + 1024]);
        c.contentLength = -1; // unknown length: the cap must be enforced while copying
        FetchResult<ArtManifest> r = source(new Net(c), FIXED_CLOCK, NOOP_WATCHDOG).manifest();
        assertFalse(r.isOk());
    }

    @Test public void slowRedirectsHitDeadlineBeforeCopy() {
        // clock readings: deadline-calc=0, hop0 remaining=100, after-headers=29000 (follow), hop1 remaining=31000(<=0)
        ArtSource.Clock clock = scriptClock(0, 100, 29_000, 31_000);
        FakeConn secondBody = okConn(goodManifest()); // must never be opened or read
        Net net = new Net(redirectConn(302, "https://art.jmwork.ru/manifest.json"), secondBody);
        FetchResult<ArtManifest> r = source(net, clock, NOOP_WATCHDOG).manifest();
        assertFalse(r.isOk());
        // the single budget spanned the redirect hop and aborted BEFORE the copy loop: the
        // second connection (which would have served a valid body) is never even opened.
        assertEquals(1, net.opens.get());
    }

    // ---- independent abort of blocked I/O (watchdog + cancel) ---------------------------------

    @Test public void stalledHeadersAbortedByWatchdog() throws Exception {
        FakeConn c = new FakeConn();
        c.blockHeaders = true;
        CapturingWatchdog wd = new CapturingWatchdog();
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<ArtManifest>> f = ex.submit(src::manifest);
            wd.fireAfterArming();                 // deadline watchdog disconnects from outside the fetch thread
            FetchResult<ArtManifest> r = f.get(3, TimeUnit.SECONDS);
            assertFalse(r.isOk());                // failed cleanly, did not hang
        } finally {
            ex.shutdownNow();
        }
    }

    @Test public void blockedReadAbortedByCancel() throws Exception {
        FakeConn c = okConn(new byte[0]);
        c.blockBody = true;
        ArtSource src = source(new Net(c), FIXED_CLOCK, NOOP_WATCHDOG);
        ArtManifest.Entry entry = new ArtManifest.Entry(1L, "t", "y", Collections.emptyList(), FILE, SHA);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<File>> f = ex.submit(() -> src.image(entry));
            assertTrue(c.readEntered.await(3, TimeUnit.SECONDS)); // body read is blocked
            src.cancel();                                         // abort from the outside
            FetchResult<File> r = f.get(3, TimeUnit.SECONDS);
            assertFalse(r.isOk());
            File images = new File(new File(tmp.getRoot(), "art-cache"), "images");
            assertFalse("nothing committed", new File(images, FILE).isFile());
            assertEquals("the unique .part was cleaned up", 0, strayParts());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test public void blockedReadAbortedByDeadlineWatchdog() throws Exception {
        FakeConn c = okConn(new byte[0]);
        c.blockBody = true;
        CapturingWatchdog wd = new CapturingWatchdog();
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ArtManifest.Entry entry = new ArtManifest.Entry(1L, "t", "y", Collections.emptyList(), FILE, SHA);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<File>> f = ex.submit(() -> src.image(entry));
            assertTrue(c.readEntered.await(3, TimeUnit.SECONDS));
            wd.fireAfterArming();
            FetchResult<File> r = f.get(3, TimeUnit.SECONDS);
            assertFalse(r.isOk());
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- image: cache / integrity -------------------------------------------------------------

    @Test public void imageHappyPathFetchesVerifiesAndCommits() {
        byte[] bytes = "painting-content".getBytes();
        String sha1 = ArtCache.sha1Hex(bytes);
        String file = "7-" + sha1.substring(0, 12) + ".jpg";
        ArtManifest.Entry entry = new ArtManifest.Entry(7L, "t", "y", Collections.emptyList(), file, sha1);
        Net net = new Net(okConn(bytes));
        FetchResult<File> r = source(net, FIXED_CLOCK, NOOP_WATCHDOG).image(entry);
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertTrue(r.value().isFile());
        assertEquals(1, net.opens.get());
    }

    @Test public void imageCacheHitSkipsNetwork() throws IOException {
        byte[] bytes = "cached".getBytes();
        String sha1 = ArtCache.sha1Hex(bytes);
        String file = "8-" + sha1.substring(0, 12) + ".jpg";
        File images = new File(new File(tmp.getRoot(), "art-cache"), "images");
        assertTrue(images.mkdirs());
        Files.write(new File(images, file).toPath(), bytes);
        ArtManifest.Entry entry = new ArtManifest.Entry(8L, "t", "y", Collections.emptyList(), file, sha1);
        Net net = new Net(); // empty: opening any connection would throw
        FetchResult<File> r = source(net, FIXED_CLOCK, NOOP_WATCHDOG).image(entry);
        assertTrue(r.isOk());
        assertEquals("no network on a warm hit", 0, net.opens.get());
    }

    @Test public void corruptCacheEntryIsRefetched() throws IOException {
        byte[] bytes = "fresh".getBytes();
        String sha1 = ArtCache.sha1Hex(bytes);
        String file = "9-" + sha1.substring(0, 12) + ".jpg";
        File images = new File(new File(tmp.getRoot(), "art-cache"), "images");
        assertTrue(images.mkdirs());
        Files.write(new File(images, file).toPath(), "tampered".getBytes()); // wrong content
        ArtManifest.Entry entry = new ArtManifest.Entry(9L, "t", "y", Collections.emptyList(), file, sha1);
        Net net = new Net(okConn(bytes));
        FetchResult<File> r = source(net, FIXED_CLOCK, NOOP_WATCHDOG).image(entry);
        assertTrue(r.isOk());
        assertEquals("refetched after integrity failure", 1, net.opens.get());
    }

    // ---- regression: EOF bypasses cancel/deadline (P2 ArtSource.java:228) ---------------------

    @Test public void eofCoincidingWithCancelIsNotSuccess() {
        byte[] bytes = "img".getBytes();
        String sha1 = ArtCache.sha1Hex(bytes);
        String file = "11-" + sha1.substring(0, 12) + ".jpg";
        ArtManifest.Entry entry = new ArtManifest.Entry(11L, "t", "y", Collections.emptyList(), file, sha1);
        FakeConn c = okConn(bytes);
        ArtSource src = source(new Net(c), FIXED_CLOCK, NOOP_WATCHDOG);
        c.onFirstRead = src::cancel;          // cancel fires exactly as the body read begins, then EOF
        FetchResult<File> r = src.image(entry);
        assertFalse("EOF coinciding with cancellation must not be reported as success", r.isOk());
        assertFalse(imageFile(file).isFile());
    }

    @Test public void eofAfterDeadlineIsNotSuccess() {
        byte[] bytes = "img".getBytes();
        String sha1 = ArtCache.sha1Hex(bytes);
        String file = "12-" + sha1.substring(0, 12) + ".jpg";
        ArtManifest.Entry entry = new ArtManifest.Entry(12L, "t", "y", Collections.emptyList(), file, sha1);
        FakeConn c = okConn(new byte[0]);     // empty body → EOF on first read
        // pre-read check still inside budget (reads EOF), post-EOF check is past the deadline
        ArtSource.Clock clock = scriptClock(0, 100, 200, 100, 31_000);
        FetchResult<File> r = source(new Net(c), clock, NOOP_WATCHDOG).image(entry);
        assertFalse("EOF past the deadline must not be reported as success", r.isOk());
        assertFalse(imageFile(file).isFile());
    }

    // ---- regression: admission control at the common fetch boundary (P2 ArtSource.java:117) ---

    @Test public void manifestFetchesRespectConcurrencyCap() throws Exception {
        AtomicInteger live = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        FakeConn[] conns = new FakeConn[3];
        for (int i = 0; i < 3; i++) {
            FakeConn c = okConn(goodManifest());
            c.liveGauge = live;
            c.maxGauge = max;
            c.headerDwellMs = 60;             // widen the live window so overlap is observable
            conns[i] = c;
        }
        Net net = new Net(conns);
        ArtSource src = source(net, FIXED_CLOCK, NOOP_WATCHDOG);
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            Future<?>[] fs = new Future<?>[3];
            for (int i = 0; i < 3; i++) fs[i] = ex.submit(src::manifest);
            for (Future<?> f : fs) f.get(5, TimeUnit.SECONDS);
            assertTrue("manifest fetches are capped at 2 concurrent, saw " + max.get(), max.get() <= 2);
            assertEquals("all three fetches ran", 3, net.opens.get());
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- regression: concurrent writes never share a temp / corrupt a dest (P1 ArtCache) ------

    @Test public void concurrentWritesForSameEntryStayConsistent() throws Exception {
        byte[] bytes = "same-entry-image-bytes".getBytes();
        String sha1 = ArtCache.sha1Hex(bytes);
        String file = "13-" + sha1.substring(0, 12) + ".jpg";
        ArtManifest.Entry entry = new ArtManifest.Entry(13L, "t", "y", Collections.emptyList(), file, sha1);
        FakeConn c1 = okConn(bytes);
        FakeConn c2 = okConn(bytes);
        c1.headerDwellMs = 40;
        c2.headerDwellMs = 40;
        ArtSource src = source(new Net(c1, c2), FIXED_CLOCK, NOOP_WATCHDOG);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<FetchResult<File>> f1 = ex.submit(() -> src.image(entry));
            Future<FetchResult<File>> f2 = ex.submit(() -> src.image(entry));
            FetchResult<File> r1 = f1.get(5, TimeUnit.SECONDS);
            FetchResult<File> r2 = f2.get(5, TimeUnit.SECONDS);
            assertTrue(r1.isOk() ? "" : r1.error(), r1.isOk());
            assertTrue(r2.isOk() ? "" : r2.error(), r2.isOk());
            assertArrayEquals("installed file is one complete verified image",
                    bytes, Files.readAllBytes(imageFile(file).toPath()));
            assertEquals("no stray .part from either writer", 0, strayParts());
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- regression: cancel reaches work queued on the semaphore (P2 ArtSource.java:164) ------

    @Test public void cancelWhileQueuedRejectsBeforeConnect() throws Exception {
        FakeConn c1 = okConn(new byte[0]);
        c1.blockBody = true;                              // occupies one permit, blocks in the body read
        FakeConn c2 = okConn(new byte[0]);
        c2.blockBody = true;                              // occupies the other permit
        FakeConn c3 = okConn("would-succeed".getBytes()); // the queued request's connection — must never open
        Net net = new Net(c1, c2, c3);
        ArtSource src = source(net, FIXED_CLOCK, NOOP_WATCHDOG);
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            Future<FetchResult<File>> f1 = ex.submit(() -> src.image(entry(21)));
            Future<FetchResult<File>> f2 = ex.submit(() -> src.image(entry(22)));
            assertTrue(c1.readEntered.await(3, TimeUnit.SECONDS));
            assertTrue(c2.readEntered.await(3, TimeUnit.SECONDS)); // both permits held, blocked in read
            Future<FetchResult<File>> f3 = ex.submit(() -> src.image(entry(23)));
            // wait until the third request is registered (it registers BEFORE waiting on the semaphore)
            long until = System.currentTimeMillis() + 3000;
            while (src.activeCount() < 3 && System.currentTimeMillis() < until) Thread.sleep(5);
            assertEquals("third request registered while queued", 3, src.activeCount());

            src.cancel(); // aborts the two blocked fetches AND marks the queued third cancelled

            FetchResult<File> r3 = f3.get(3, TimeUnit.SECONDS);
            f1.get(3, TimeUnit.SECONDS);
            f2.get(3, TimeUnit.SECONDS);
            assertFalse("a request cancelled while queued must fail", r3.isOk());
            assertNull("the queued request must never open its connection", c3.openedUrl);
            assertEquals("only the two admitted fetches opened connections", 2, net.opens.get());
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static ArtManifest.Entry entry(int id) {
        String file = id + "-" + SHA.substring(0, 12) + ".jpg";
        return new ArtManifest.Entry(id, "t", "y", Collections.emptyList(), file, SHA);
    }

    private File imageFile(String name) {
        return new File(new File(new File(tmp.getRoot(), "art-cache"), "images"), name);
    }

    private int strayParts() {
        File images = new File(new File(tmp.getRoot(), "art-cache"), "images");
        File[] parts = images.listFiles((d, n) -> n.endsWith(".part"));
        return parts == null ? 0 : parts.length;
    }

    private static FakeConn okConn(byte[] body) {
        return okConn(body, body == null ? -1 : body.length);
    }

    private static FakeConn okConn(byte[] body, long contentLength) {
        try {
            return FakeConn.ok(body == null ? new byte[0] : body, contentLength);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static FakeConn statusConn(int code) {
        try {
            return FakeConn.status(code);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static FakeConn redirectConn(int code, String location) {
        try {
            return FakeConn.redirect(code, location);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static ArtSource.Clock scriptClock(long... times) {
        return new ArtSource.Clock() {
            private int i;
            @Override public long nowMs() {
                long v = times[Math.min(i, times.length - 1)];
                i++;
                return v;
            }
        };
    }
}
