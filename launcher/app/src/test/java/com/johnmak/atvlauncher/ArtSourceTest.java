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
import java.util.concurrent.RejectedExecutionException;
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
        boolean stall;                    // getResponseCode blocks (connect/headers stalled), modelling Android semantics:
        volatile boolean responseStarted; //   a disconnect BEFORE the response started is a no-op; only a POST-start one is effective
        Runnable onFirstRead;             // runs once at the start of the first body read, then EOF
        Runnable beforeResponse;          // runs at the last pre-connect checkpoint (User-Agent set), before getResponseCode
        CountDownLatch holdBody;          // if set, the body read blocks here, SWALLOWING interrupts and IGNORING disconnect
        CountDownLatch inBody;            // counted down when the body read is entered
        CountDownLatch blockDisconnect;   // if set, disconnect() blocks AFTER releasing (models a blocking close)
        CountDownLatch disconnectGate;    // if set, disconnect() blocks FIRST, before releasing any worker latch
        boolean interruptibleRead;        // body read blocks on an interruptible sleep (responds ONLY to interrupt)
        final CountDownLatch readInterrupted = new CountDownLatch(1); // the blocked read observed an interrupt
        AtomicInteger liveGauge;          // concurrency probe: live connections (headers..disconnect)
        AtomicInteger maxGauge;
        long headerDwellMs;               // widen the live window so overlapping fetches are observable
        String openedUrl;
        volatile boolean disconnected;
        final CountDownLatch released = new CountDownLatch(1);
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch stallEntered = new CountDownLatch(1);   // getResponseCode's stall was entered
        final CountDownLatch effectiveAbort = new CountDownLatch(1); // a POST-start disconnect (the real kill) arrived

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
            if (stall) {
                // The connection has now STARTED; a disconnect from here on is effective (Android semantics). Block
                // until an effective abort or an interrupt — modelling a connect/header stall that read-timeout cannot
                // bound, so only the absolute-deadline task can kill it.
                responseStarted = true;
                stallEntered.countDown();
                try {
                    effectiveAbort.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("stalled connection aborted");
            }
            return status;
        }

        @Override public void setRequestProperty(String key, String value) {
            if (beforeResponse != null) beforeResponse.run(); // the production fetch sets User-Agent right before getResponseCode
        }

        @Override public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }

        @Override public long getContentLengthLong() {
            return contentLength;
        }

        @Override public InputStream getInputStream() {
            if (interruptibleRead) {
                return new InputStream() {
                    @Override public int read() throws IOException { return ir(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return ir(); }
                    private int ir() throws IOException {
                        readEntered.countDown();
                        try {
                            Thread.sleep(3_000); // interruptible block; ONLY an interrupt stops it in time
                        } catch (InterruptedException e) {
                            readInterrupted.countDown();        // the interrupt reached the blocked read
                            throw new IOException("interrupted mid-read");
                        }
                        throw new IOException("read timed out (not interrupted)");
                    }
                };
            }
            if (holdBody != null) {
                return new InputStream() {
                    @Override public int read() throws IOException { return hold(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return hold(); }
                    private int hold() throws IOException {
                        if (inBody != null) inBody.countDown();
                        // Block ignoring interrupt AND disconnect until the test releases — a worker slow to notice
                        // cancellation, so the reaper must await drain before disposing the watchdog.
                        while (holdBody.getCount() > 0) {
                            try { holdBody.await(); } catch (InterruptedException e) { /* swallow; keep holding */ }
                        }
                        throw new IOException("released");
                    }
                };
            }
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
            if (disconnectGate != null) { // block BEFORE releasing anything (models a disconnect that stalls at the socket)
                try { disconnectGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            boolean first = !disconnected;
            disconnected = true;
            if (first && liveGauge != null) liveGauge.decrementAndGet();
            released.countDown();
            if (responseStarted) effectiveAbort.countDown(); // pre-start disconnect is a no-op; only post-start is effective
            if (blockDisconnect != null) {
                try { blockDisconnect.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        @Override public boolean usingProxy() {
            return false;
        }
    }

    private static final ArtSource.Clock FIXED_CLOCK = () -> 0L;

    /** A controllable monotonic clock: the reaper budget must track THIS, never wall-clock. */
    private static final class MutableClock implements ArtSource.Clock {
        volatile long ms;
        @Override public long nowMs() { return ms; }
        void set(long v) { ms = v; }
    }
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

    /**
     * Captures the armed per-request deadline task (fireable on demand) AND records disposal via {@link #shutdown()}
     * with a latch — so both the deadline-kill and the reaper-disposes-after-drain behaviours are observable.
     */
    private static final class RecordingWatchdog implements ArtSource.Watchdog {
        final AtomicInteger shutdowns = new AtomicInteger();
        final CountDownLatch disposed = new CountDownLatch(1);
        private volatile Runnable onFire;
        private final CountDownLatch armed = new CountDownLatch(1);

        @Override public Arm arm(long delayMs, Runnable fire) {
            this.onFire = fire;
            armed.countDown();
            return () -> { };
        }

        @Override public void shutdown() {
            shutdowns.incrementAndGet();
            disposed.countDown();
        }

        void fireAfterArming() throws InterruptedException {
            assertTrue("watchdog was armed", armed.await(3, TimeUnit.SECONDS));
            onFire.run();
        }

        /** Fires the armed callback on its OWN daemon thread (as the production watchdog thread would). */
        void fireAfterArmingOnNewThread() throws InterruptedException {
            assertTrue("watchdog was armed", armed.await(3, TimeUnit.SECONDS));
            Thread t = new Thread(onFire, "test-deadline-fire");
            t.setDaemon(true);
            t.start();
        }
    }

    private ArtSource source(Net net, ArtSource.Clock clock, ArtSource.Watchdog wd) {
        return new ArtSource(new ArtCache(tmp.getRoot()), "ua-test", net, clock, wd);
    }

    // ---- teardown: watchdog disposed by the reaper AFTER drain (Codex rounds 2 + 5) -----------

    @Test public void shutdownDisposesTheWatchdogViaReaper() throws Exception {
        RecordingWatchdog wd = new RecordingWatchdog();
        source(new Net(), FIXED_CLOCK, wd).shutdown(); // idle source: the reaper drains immediately then disposes
        assertTrue("the reaper disposes the watchdog after drain", wd.disposed.await(3, TimeUnit.SECONDS));
        assertEquals(1, wd.shutdowns.get());
    }

    @Test public void shutdownIsIdempotent() throws Exception {
        RecordingWatchdog wd = new RecordingWatchdog();
        ArtSource src = source(new Net(), FIXED_CLOCK, wd);
        src.shutdown();
        src.shutdown(); // terminal + idempotent: the second call returns early → one reaper → one disposal
        assertTrue(wd.disposed.await(3, TimeUnit.SECONDS));
        assertEquals("watchdog disposed exactly once", 1, wd.shutdowns.get());
    }

    @Test public void shutdownIsTerminalLateRequestsNeverRun() {
        // A request that arrives AFTER close must be refused at registration — it must never open a connection.
        Net net = new Net(okConn(goodManifest())); // if any connection opened, this scripted one would be consumed
        ArtSource src = source(net, FIXED_CLOCK, NOOP_WATCHDOG);
        src.shutdown();
        FetchResult<ArtManifest> r = src.manifest();
        assertFalse("a request on a closed source must not run", r.isOk());
        assertEquals("no connection opened after close", 0, net.opens.get());
        assertEquals("the late request was never registered", 0, src.activeCount());
    }

    @Test public void rejectedWatchdogArmingCancelsBeforeIO() {
        // If the deadline watchdog cannot be armed (executor disposed by a concurrent shutdown), the fetch must bail
        // WITHOUT starting unguarded network I/O — not proceed with no absolute deadline.
        ArtSource.Watchdog rejecting = (delayMs, onFire) -> {
            throw new RejectedExecutionException("watchdog disposed");
        };
        Net net = new Net(okConn(goodManifest()));
        FetchResult<ArtManifest> r = source(net, FIXED_CLOCK, rejecting).manifest();
        assertFalse("a request whose deadline watchdog cannot arm must not run unguarded", r.isOk());
        assertEquals("no connection opened when the watchdog could not be armed", 0, net.opens.get());
    }

    @Test public void shutdownRacingConnectStartDiscardsTheResponse() throws Exception {
        // Deterministic: pause the worker at the last pre-connect checkpoint (right AFTER the atomic start gate),
        // run a full shutdown in that window, then resume. The response the worker then reads must be DISCARDED —
        // nothing is served and no manifest is written — proving the gate→connect window is handled (Codex round 4).
        CountDownLatch pastGate = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        FakeConn c = okConn(goodManifest());
        c.beforeResponse = () -> {
            pastGate.countDown();          // the worker has passed the start gate and is about to connect
            try {
                assertTrue("test released the worker", resume.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ArtSource src = source(new Net(c), FIXED_CLOCK, NOOP_WATCHDOG);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<ArtManifest>> f = ex.submit(src::manifest);
            assertTrue("worker reached the pre-connect checkpoint", pastGate.await(3, TimeUnit.SECONDS));
            src.shutdown();                // close + abort in-flight, inside the gate->connect window (non-blocking)
            resume.countDown();            // worker resumes and would start the connection
            FetchResult<ArtManifest> r = f.get(3, TimeUnit.SECONDS);
            assertFalse("a response fetched after shutdown must be discarded", r.isOk());
            assertFalse("no manifest written from a post-shutdown response",
                    new ArtCache(tmp.getRoot()).readManifest().isOk());
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- brain design of record: deadline task bound to the request, non-blocking reaper (round 5) ----

    @Test public void postShutdownGetResponseCodeIsKilledByTheDeadlineTask() throws Exception {
        // (a) Worker paused right before getResponseCode; shutdown runs (its disconnect hits a still-UNSTARTED socket =
        // Android no-op); worker resumes and ENTERS getResponseCode AFTER close, unguarded but for the armed deadline
        // task. Firing that task (survives shutdown — never disposed inline) is what kills it.
        RecordingWatchdog wd = new RecordingWatchdog();
        CountDownLatch pastGate = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        FakeConn c = okConn(goodManifest());
        c.stall = true;
        c.beforeResponse = () -> {
            pastGate.countDown();
            // non-interruptible wait: shutdown() interrupts us, but we must stay paused until the test resumes us, so
            // the worker provably enters getResponseCode AFTER shutdown has completed.
            while (proceed.getCount() > 0) {
                try { proceed.await(); } catch (InterruptedException e) { /* swallow; keep waiting */ }
            }
        };
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<ArtManifest>> f = ex.submit(src::manifest);
            assertTrue("worker reached the pre-connect checkpoint", pastGate.await(3, TimeUnit.SECONDS));
            src.shutdown();                                   // disconnect is a no-op here (not started yet)
            proceed.countDown();                              // worker resumes and enters getResponseCode after close
            assertTrue("getResponseCode entered after shutdown (the refuted window is real)",
                    c.stallEntered.await(3, TimeUnit.SECONDS));
            wd.fireAfterArming();                             // the armed absolute-deadline task fires -> interrupt + effective disconnect
            FetchResult<ArtManifest> r = f.get(3, TimeUnit.SECONDS);
            assertFalse("post-shutdown getResponseCode was killed by the deadline task, not left running", r.isOk());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test public void tricklingHeadersKilledAtTheAbsoluteDeadline() throws Exception {
        // (b) Headers that trickle forever never trip read-timeout (it bounds only inter-byte gaps). Only the
        // per-request absolute-deadline task bounds it: firing it kills the stalled getResponseCode.
        RecordingWatchdog wd = new RecordingWatchdog();
        FakeConn c = okConn(goodManifest());
        c.stall = true;
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<ArtManifest>> f = ex.submit(src::manifest);
            assertTrue("getResponseCode entered and stalled", c.stallEntered.await(3, TimeUnit.SECONDS));
            wd.fireAfterArming();                              // absolute deadline reached
            FetchResult<ArtManifest> r = f.get(3, TimeUnit.SECONDS);
            assertFalse("trickling headers killed at the absolute deadline", r.isOk());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test public void stalledConnectKilledAtTheAbsoluteDeadline() throws Exception {
        // (c) A connect that never establishes (getResponseCode never returns a status) is bounded only by the
        // absolute-deadline task. The connection was opened but produces nothing and writes no manifest.
        RecordingWatchdog wd = new RecordingWatchdog();
        FakeConn c = okConn(goodManifest());
        c.stall = true;
        Net net = new Net(c);
        ArtSource src = source(net, FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<ArtManifest>> f = ex.submit(src::manifest);
            assertTrue("connect entered and stalled", c.stallEntered.await(3, TimeUnit.SECONDS));
            wd.fireAfterArming();
            FetchResult<ArtManifest> r = f.get(3, TimeUnit.SECONDS);
            assertFalse("stalled connect killed at the absolute deadline", r.isOk());
            assertEquals("the connection was opened once", 1, net.opens.get());
            assertFalse("no manifest written from a killed connect",
                    new ArtCache(tmp.getRoot()).readManifest().isOk());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test public void reaperDisposesWatchdogOnlyAfterTheWorkerExits() throws Exception {
        // (d) A worker that ignores interrupt AND disconnect until the test releases it. The reaper MUST NOT dispose
        // the watchdog while that worker is in flight — only after it drains.
        RecordingWatchdog wd = new RecordingWatchdog();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inBody = new CountDownLatch(1);
        FakeConn c = okConn(new byte[0], 0);
        c.holdBody = release;
        c.inBody = inBody;
        String sha1 = ArtCache.sha1Hex(new byte[0]);
        String file = "14-" + sha1.substring(0, 12) + ".jpg";
        ArtManifest.Entry entry = new ArtManifest.Entry(14L, "t", "y", Collections.emptyList(), file, sha1);
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<File>> f = ex.submit(() -> src.image(entry));
            assertTrue("worker is in the body read (in flight)", inBody.await(3, TimeUnit.SECONDS));
            src.shutdown();                                    // reaper submitted; worker still held in flight
            assertFalse("watchdog NOT disposed while a worker is in flight",
                    wd.disposed.await(300, TimeUnit.MILLISECONDS));
            assertEquals(0, wd.shutdowns.get());
            release.countDown();                               // worker exits now
            assertTrue("watchdog disposed after the worker exits", wd.disposed.await(3, TimeUnit.SECONDS));
            assertEquals(1, wd.shutdowns.get());
            f.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test public void shutdownDoesNotBlockOnAnInFlightWorker() throws Exception {
        // (e) onDestroy/shutdown is non-blocking: it must NOT join a still-blocked worker. It returns promptly after
        // flipping closed, aborting in-flight, and submitting the reaper.
        RecordingWatchdog wd = new RecordingWatchdog();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inBody = new CountDownLatch(1);
        FakeConn c = okConn(new byte[0], 0);
        c.holdBody = release;
        c.inBody = inBody;
        String sha1 = ArtCache.sha1Hex(new byte[0]);
        String file = "15-" + sha1.substring(0, 12) + ".jpg";
        ArtManifest.Entry entry = new ArtManifest.Entry(15L, "t", "y", Collections.emptyList(), file, sha1);
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> src.image(entry));
            assertTrue(inBody.await(3, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            src.shutdown();                                    // must NOT join the (still-blocked) worker
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue("shutdown returned promptly without joining the worker (" + elapsedMs + "ms)", elapsedMs < 1_000);
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    // ---- round 6: three bounded teardown fixes ------------------------------------------------

    @Test public void shutdownStaysNonBlockingWhenDisconnectBlocks() throws Exception {
        // FIX #1: the abort's disconnect() runs on a background thread, so even a BLOCKING disconnect cannot block the
        // caller (onDestroy). shutdown() returns promptly after flipping `closed` + submitting the reaper/abort.
        RecordingWatchdog wd = new RecordingWatchdog();
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch blockDisc = new CountDownLatch(1);
        FakeConn c = okConn(new byte[0], 0);
        c.holdBody = releaseBody;
        c.inBody = inBody;
        c.blockDisconnect = blockDisc;              // the abort's disconnect() will block
        String sha1 = ArtCache.sha1Hex(new byte[0]);
        ArtManifest.Entry entry = new ArtManifest.Entry(16L, "t", "y", Collections.emptyList(),
                "16-" + sha1.substring(0, 12) + ".jpg", sha1);
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> src.image(entry));
            assertTrue(inBody.await(3, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            src.shutdown();                         // the blocking disconnect is off the caller thread
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue("shutdown non-blocking despite a blocking disconnect (" + elapsedMs + "ms)", elapsedMs < 1_000);
        } finally {
            blockDisc.countDown();
            releaseBody.countDown();
            ex.shutdownNow();
        }
    }

    @Test public void staleDeadlineCallbackDoesNotInterruptTheReusedThread() throws Exception {
        // FIX #2: a cancellation/deadline callback that fires AFTER its request retired must NOT interrupt the pooled
        // thread that has moved on to a different task. Retirement and interrupt delivery are serialized under the
        // request lock, so the stale abort sees `retired` and skips the interrupt.
        RecordingWatchdog wd = new RecordingWatchdog(); // captures the first request's deadline callback
        ArtSource src = source(new Net(okConn(goodManifest())), FIXED_CLOCK, wd);
        ExecutorService pool = Executors.newSingleThreadExecutor(); // ONE reused pooled thread
        try {
            // 1) first request runs to completion and RETIRES (run()'s finally called retire()).
            assertTrue(pool.submit(src::manifest).get(3, TimeUnit.SECONDS).isOk());
            // 2) a SECOND task on the SAME pooled thread; the stale callback must not interrupt it.
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch fired = new CountDownLatch(1);
            Future<Boolean> f2 = pool.submit(() -> {
                started.countDown();
                assertTrue(fired.await(3, TimeUnit.SECONDS));
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            wd.fireAfterArming();   // fire the first (now stale, retired) request's deadline callback -> abort()
            fired.countDown();
            assertFalse("a stale callback must not interrupt the reused pooled thread", f2.get(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void reaperBudgetUsesMonotonicClockNotWallClock() throws Exception {
        // FIX #3: the reaper budget is driven by the injected MONOTONIC clock, never System.currentTimeMillis — so a
        // forward wall-clock correction can never expire it early. Frozen clock -> no expiry (real wall-clock time
        // passes, yet no disposal); advancing the injected clock past the budget -> disposal.
        RecordingWatchdog wd = new RecordingWatchdog();
        MutableClock clock = new MutableClock(); // starts at 0
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        FakeConn c = okConn(new byte[0], 0);
        c.holdBody = releaseBody;
        c.inBody = inBody;
        String sha1 = ArtCache.sha1Hex(new byte[0]);
        ArtManifest.Entry entry = new ArtManifest.Entry(17L, "t", "y", Collections.emptyList(),
                "17-" + sha1.substring(0, 12) + ".jpg", sha1);
        ArtSource src = source(new Net(c), clock, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> src.image(entry));
            assertTrue(inBody.await(3, TimeUnit.SECONDS));
            src.shutdown();                     // reaper budget = clock.now()(=0) + REQUEST_DEADLINE_MS + grace
            // real wall-clock time passes, but the injected clock is frozen -> the reaper does NOT expire/dispose
            assertFalse("frozen monotonic clock: no premature disposal", wd.disposed.await(400, TimeUnit.MILLISECONDS));
            assertEquals(0, wd.shutdowns.get());
            // advance the injected monotonic clock past the budget -> the reaper now expires and disposes
            clock.set(ArtSource.REQUEST_DEADLINE_MS + ArtSource.REAPER_GRACE_MS + 1_000);
            assertTrue("advancing the monotonic clock past the budget disposes the watchdog",
                    wd.disposed.await(2, TimeUnit.SECONDS));
            assertEquals(1, wd.shutdowns.get());
        } finally {
            releaseBody.countDown();
            ex.shutdownNow();
        }
    }

    // ---- round 7: abort delivers interrupt BEFORE the (possibly blocking) disconnect ----------

    @Test public void blockingDisconnectDoesNotStallInterruptDelivery() throws Exception {
        // FIX (round 7): abort() interrupts the worker FIRST (under the retire lock), THEN disconnects. So even a
        // disconnect() that blocks BEFORE releasing anything cannot stall the interrupt that actually stops the
        // request. The abort is fired on its own thread (as the production watchdog/teardown thread would be).
        RecordingWatchdog wd = new RecordingWatchdog();
        FakeConn c = okConn(new byte[0], 0);
        c.interruptibleRead = true;           // the body read responds ONLY to an interrupt
        c.disconnectGate = new CountDownLatch(1); // disconnect() blocks FIRST, before releasing the read
        String sha1 = ArtCache.sha1Hex(new byte[0]);
        ArtManifest.Entry entry = new ArtManifest.Entry(18L, "t", "y", Collections.emptyList(),
                "18-" + sha1.substring(0, 12) + ".jpg", sha1);
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> src.image(entry));
            assertTrue("worker blocked in the body read", c.readEntered.await(3, TimeUnit.SECONDS));
            wd.fireAfterArmingOnNewThread();  // deadline callback -> abort(): interrupt FIRST, then the blocked disconnect
            assertTrue("the interrupt reached the blocked read despite a blocking disconnect()",
                    c.readInterrupted.await(3, TimeUnit.SECONDS));
        } finally {
            c.disconnectGate.countDown();     // let the blocked disconnect(s) proceed so the worker can unwind
            ex.shutdownNow();
        }
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

    // ---- retire-abort grace: shutdownWithin(graceMs) (Model B) --------------------------------

    /** Captures every watchdog arm by its delay so the test can fire a specific one. */
    private static final class MultiWatchdog implements ArtSource.Watchdog {
        final java.util.List<Long> delays = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.Map<Long, Runnable> byDelay = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public Arm arm(long delayMs, Runnable onFire) {
            delays.add(delayMs);
            byDelay.put(delayMs, onFire);
            return () -> { };
        }

        void fire(long delayMs) {
            Runnable r = byDelay.get(delayMs);
            if (r != null) r.run();
        }
    }

    @Test public void shutdownWithinArmsAGraceReAbortButPlainShutdownDoesNot() {
        MultiWatchdog graced = new MultiWatchdog();
        source(new Net(), FIXED_CLOCK, graced).shutdownWithin(1_500L); // idle source: the grace is still armed
        assertTrue("shutdownWithin arms a grace re-abort at the given delay", graced.delays.contains(1_500L));

        MultiWatchdog plain = new MultiWatchdog();
        source(new Net(), FIXED_CLOCK, plain).shutdown(); // plain shutdown arms NO extra grace (behaviour unchanged)
        assertTrue("plain shutdown() arms no grace re-abort", plain.delays.isEmpty());
    }

    @Test public void shutdownWithinGraceReAbortVacatesAStalledPost393Request() throws Exception {
        // The worker is paused right before getResponseCode when shutdownWithin runs, so the immediate abort's
        // disconnect hits a still-unstarted socket (Android no-op). The worker then enters getResponseCode and stalls.
        // Firing the GRACE re-abort (not the 30s deadline) disconnects the now-started connection and vacates it.
        MultiWatchdog wd = new MultiWatchdog();
        CountDownLatch pastGate = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        FakeConn c = okConn(goodManifest());
        c.stall = true;
        c.beforeResponse = () -> {
            pastGate.countDown();
            while (proceed.getCount() > 0) {
                try { proceed.await(); } catch (InterruptedException e) { /* stay paused until resumed */ }
            }
        };
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<FetchResult<ArtManifest>> f = ex.submit(src::manifest);
            assertTrue("worker reached the pre-connect checkpoint", pastGate.await(3, TimeUnit.SECONDS));
            src.shutdownWithin(1_500L);                       // immediate abort = no-op here; grace armed at 1500
            proceed.countDown();                              // worker resumes and stalls inside getResponseCode
            assertTrue("getResponseCode entered and stalled", c.stallEntered.await(3, TimeUnit.SECONDS));
            wd.fire(1_500L);                                  // the GRACE re-abort fires -> effective disconnect
            FetchResult<ArtManifest> r = f.get(3, TimeUnit.SECONDS);
            assertFalse("the grace re-abort vacated the stalled :393 request (not the 30s deadline)", r.isOk());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test public void graceReAbortDoesNotBlockTheTimerThreadOnABlockingDisconnect() throws Exception {
        // The grace fires on the shared watchdog TIMER thread, which must never block. Even with a disconnect() that
        // blocks, firing the grace must return promptly (interrupt-first on the timer thread; blocking disconnect
        // offloaded to a separate thread), and the interrupt must still reach the blocked read.
        MultiWatchdog wd = new MultiWatchdog();
        FakeConn c = okConn(new byte[0]);
        c.interruptibleRead = true;               // the body read responds ONLY to an interrupt
        c.disconnectGate = new CountDownLatch(1); // every disconnect() BLOCKS until released
        ArtManifest.Entry entry = new ArtManifest.Entry(90L, "t", "y", Collections.emptyList(), FILE, SHA);
        ArtSource src = source(new Net(c), FIXED_CLOCK, wd);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            ex.submit(() -> src.image(entry));
            assertTrue("worker blocked in the body read", c.readEntered.await(3, TimeUnit.SECONDS));
            src.shutdownWithin(1_500L);           // arms the grace at 1500 on the watchdog
            long t0 = System.nanoTime();
            wd.fire(1_500L);                      // fire the grace AS IF on the timer thread
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue("the grace fire returned promptly despite a blocking disconnect (" + ms + "ms)", ms < 1_000);
            assertTrue("the interrupt still reached the blocked read (interrupt-first on the timer thread)",
                    c.readInterrupted.await(3, TimeUnit.SECONDS));
        } finally {
            c.disconnectGate.countDown();
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
