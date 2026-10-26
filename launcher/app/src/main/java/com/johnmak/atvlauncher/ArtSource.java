package com.johnmak.atvlauncher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The runtime art boundary: a guarded fetch of {@code art.jmwork.ru} content into the on-device {@link ArtCache}.
 * Reuses the {@link ApkDownloader}/{@link UpdateHosts} pattern (manual redirects, per-hop host check, byte caps,
 * atomic writes), not {@link HttpText} (which follows cross-origin redirects). Blocking; worker thread only.
 *
 * <p>Guarantees (D9/D10): one monotonic per-request deadline spanning every redirect hop, the connection, header
 * retrieval and the body copy, with per-phase remaining-time clamps over the connect/read limits; an independent
 * {@link Watchdog} and {@link #cancel()} each abort blocked I/O from outside the fetch thread by interrupting the
 * recorded worker thread AND disconnecting the live connection (the between-chunks flag is the cooperative fast path);
 * at most two in-flight fetches. The manifest is served from the on-disk last-valid copy and replaced ONLY by a
 * validated candidate.
 *
 * <p>Lifecycle (brain design of record): the watchdog's lifetime is bound to admitted in-flight requests, NOT to
 * {@link #shutdown()}. Each admitted request arms a per-request ABSOLUTE-DEADLINE task (under {@link #lifecycleLock},
 * atomically with the closed-check) that is cancelled only when the request EXITS — NEVER by shutdown. {@link #shutdown()}
 * only flips {@code closed} (blocking new admissions), best-effort aborts in-flight requests, and submits a background
 * REAPER that awaits in-flight exit (bounded by the absolute deadline + grace) and only THEN disposes the watchdog
 * executor. So a request that enters {@code getResponseCode()} after close is still killed by its own armed deadline
 * task — there is no "started but unwatched" window — and shutdown never blocks/joins inline.
 *
 * <p>The network, the monotonic clock and the deadline watchdog are injected so the production fetch loop itself runs
 * under test against controlled connections and time.
 */
final class ArtSource {
    private static final int MAX_REDIRECTS = 5;
    static final long REQUEST_DEADLINE_MS = 30_000;
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_IN_FLIGHT = 2;
    /** The reaper awaits in-flight exit for at most the absolute deadline plus this grace before disposing the watchdog. */
    static final long REAPER_GRACE_MS = 2_000;
    /** The reaper re-checks the monotonic drain budget at least this often (also woken promptly by {@link #unregister}). */
    private static final long REAPER_POLL_MS = 50;

    /** Opens a connection for a URL; the single network seam. */
    interface Connections {
        HttpURLConnection open(String url) throws IOException;
    }

    /** A monotonic clock in milliseconds (never wall time — immune to clock jumps). */
    interface Clock {
        long nowMs();
    }

    /** Schedules {@code onFire} once after {@code delayMs}; the returned handle cancels it if the fetch finishes first. */
    interface Watchdog {
        Arm arm(long delayMs, Runnable onFire);

        /** Disposes any owned scheduling resource. Default no-op (an injected watchdog that owns nothing). */
        default void shutdown() {}

        interface Arm {
            void disarm();
        }
    }

    private final ArtCache cache;
    private final Connections connections;
    private final Clock clock;
    private final Watchdog watchdog;
    private final String userAgent;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    /** Coordinates {@link #closed} with request registration so a late request can never start I/O into a closing source. */
    private final Object lifecycleLock = new Object();
    /** Terminal once {@link #shutdown()} runs. Written under {@link #lifecycleLock} (atomic with registration); volatile
     * so the workers' lock-free stop-polls (post-start, body copy) see it promptly. A closed source admits no new request. */
    private volatile boolean closed;
    /** The connections of requests still running, so {@link #cancel()}/{@link #shutdown()} can abort each from the outside.
     * All add/remove/snapshot go through {@link #lifecycleLock} (keeping the set concurrent only for the lock-free
     * {@link #activeCount()} probe). */
    private final java.util.Set<Request> active = ConcurrentHashMap.newKeySet();

    /** Production wiring: real network, a monotonic clock, a daemon watchdog thread. */
    ArtSource(ArtCache cache, String userAgent) {
        this(cache, userAgent,
                url -> (HttpURLConnection) new URL(url).openConnection(),
                () -> System.nanoTime() / 1_000_000L,
                realWatchdog());
    }

    ArtSource(ArtCache cache, String userAgent, Connections connections, Clock clock, Watchdog watchdog) {
        this.cache = cache;
        this.userAgent = userAgent;
        this.connections = connections;
        this.clock = clock;
        this.watchdog = watchdog;
    }

    /**
     * Serves the last-valid manifest, refreshing from the host first. A fetched candidate is parsed and validated in
     * memory; only a fully valid candidate atomically replaces the on-disk copy. ANY failure — network, HTTP status,
     * over-cap, parse error, zero valid entries, bad {@code base_url} — leaves last-valid untouched and playable
     * (including across process restart, since last-valid is the only manifest ever on disk). err = signal empty
     * (neither fetchable nor cached).
     */
    FetchResult<ArtManifest> manifest() {
        FetchResult<byte[]> candidate = fetchToMemory(ArtHosts.ART_MANIFEST_URL, ArtManifest.MANIFEST_MAX_BYTES);
        if (candidate.isOk() && ArtManifest.isValidCandidate(candidate.value())) {
            cache.writeManifestAtomic(candidate.value());
        }
        FetchResult<byte[]> onDisk = cache.readManifest();
        if (!onDisk.isOk()) return FetchResult.err(onDisk.error());
        return ArtManifest.parse(onDisk.value());
    }

    /**
     * The verified cache file for {@code entry}: a cache hit (sha1-checked) when present, else a guarded fetch of the
     * immutable image URL into a {@code .part}, sha1-verified against {@code entry.sha1} and atomically installed.
     * err when the pool has no space, the fetch fails, or the download is corrupt.
     */
    FetchResult<File> image(ArtManifest.Entry entry) {
        File hit = cache.hit(entry.file, entry.sha1);
        if (hit != null) return FetchResult.ok(hit);

        FetchResult<String> url = ArtHosts.imageUrl(entry.file);
        if (!url.isOk()) return url.propagate();
        if (!cache.ensureImagesDir()) return FetchResult.err("cannot create images dir");
        if (!cache.reserveForImage()) return FetchResult.err("no space for " + entry.file);

        cache.pin(entry.file);
        FetchResult<File> partResult = cache.newPart(entry.file);
        if (!partResult.isOk()) {
            cache.unpin(entry.file);
            cache.releaseReservation();
            return partResult.propagate();
        }
        File part = partResult.value();
        try {
            FetchResult<Long> fetched = fetchToFile(url.value(), ArtCache.IMAGE_MAX_BYTES, part);
            if (!fetched.isOk()) return fetched.propagate();
            return cache.commit(part, entry.file, entry.sha1);
        } finally {
            if (part.exists()) part.delete(); // cleanup on any path that did not install the temp
            cache.unpin(entry.file);
            cache.releaseReservation();
        }
    }

    /**
     * Aborts every in-flight fetch (sets the cancel flag and disconnects the live connections from the outside), without
     * closing the source — it remains usable afterwards. The snapshot is taken under {@link #lifecycleLock} for a stable
     * view; aborting runs outside the lock (disconnect is I/O).
     */
    void cancel() {
        abortSnapshot();
    }

    /**
     * Terminal teardown — after this the source is CLOSED and admits no new request. NON-BLOCKING: it never joins or
     * awaits inline (safe to call on the UI thread). Steps: (1) under {@link #lifecycleLock} flip {@code closed} (blocks
     * new admissions) and snapshot the in-flight requests; (2) best-effort abort those requests (interrupt + disconnect)
     * — this does NOT guarantee a prompt stop, so (3) submit a background REAPER that awaits in-flight exit (bounded by
     * the absolute deadline + grace) and only THEN disposes the watchdog executor. The watchdog is NEVER disposed here:
     * the per-request deadline tasks must stay armed so a request still inside {@code getResponseCode()} is killed by
     * its own deadline task. Idempotent: a second call sees {@code closed} and returns immediately (one reaper, one
     * disposal).
     */
    void shutdown() {
        List<Request> toAbort;
        synchronized (lifecycleLock) {
            if (closed) return;               // idempotent: already torn down
            closed = true;                    // SYNCHRONOUS stop signal: no new admission, and every worker check polls it
            toAbort = new ArrayList<>(active);
        }
        // Two independent daemon tasks so a BLOCKING disconnect() in the abort can never delay the reaper (watchdog
        // disposal) or the caller. The caller only flips `closed` (above, non-blocking) + submits these and returns.
        ExecutorService teardown = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "art-teardown");
            t.setDaemon(true);
            return t;
        });
        // Reaper FIRST and on its own thread: await in-flight exit (bounded, monotonic) then dispose the watchdog.
        teardown.execute(() -> {
            awaitDrain(REQUEST_DEADLINE_MS + REAPER_GRACE_MS);
            watchdog.shutdown(); // dispose ONLY after in-flight requests have exited (or the bounded grace elapsed)
        });
        // Abort work (interrupt + disconnect, possibly blocking) off the caller thread — fire-and-forget, idempotent.
        teardown.execute(() -> {
            for (Request r : toAbort) r.abort();
        });
        teardown.shutdown(); // no new tasks; the two submitted run to completion, then the daemon pool terminates
    }

    /**
     * Blocks until no request is in flight or {@code budgetMs} of MONOTONIC time elapses; woken promptly by
     * {@link #unregister} and otherwise re-checked every {@link #REAPER_POLL_MS}. Reaper-thread only. Uses the injected
     * monotonic {@link #clock} (never wall time), so a forward wall-clock correction can never expire it early and
     * dispose the watchdog before an in-flight request's own monotonic deadline.
     */
    private void awaitDrain(long budgetMs) {
        long until = clock.nowMs() + budgetMs;
        synchronized (lifecycleLock) {
            while (!active.isEmpty()) {
                long remaining = until - clock.nowMs();
                if (remaining <= 0) return;
                try {
                    lifecycleLock.wait(Math.min(remaining, REAPER_POLL_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Registers {@code request} unless the source is closed. True = registered (caller must {@link #unregister}); false = closed. */
    private boolean register(Request request) {
        synchronized (lifecycleLock) {
            if (closed) return false;
            active.add(request);
            return true;
        }
    }

    private void unregister(Request request) {
        synchronized (lifecycleLock) {
            active.remove(request);
            lifecycleLock.notifyAll(); // wake a reaper awaiting drain
        }
    }

    private void abortSnapshot() {
        List<Request> snapshot;
        synchronized (lifecycleLock) {
            snapshot = new ArrayList<>(active);
        }
        for (Request r : snapshot) r.abort();
    }

    // ---- guarded fetch loop -------------------------------------------------------------------

    private FetchResult<byte[]> fetchToMemory(String url, long maxBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FetchResult<Long> r = run(url, maxBytes, out);
        if (!r.isOk()) return r.propagate();
        return FetchResult.ok(out.toByteArray());
    }

    private FetchResult<Long> fetchToFile(String url, long maxBytes, File part) {
        try (OutputStream out = Files.newOutputStream(part.toPath())) {
            return run(url, maxBytes, out);
        } catch (IOException e) {
            return FetchResult.err("cannot open " + part + ": " + e);
        }
    }

    /**
     * The production fetch loop: manual redirects (≤{@link #MAX_REDIRECTS}), one monotonic deadline from request start
     * covering hops + connect + headers + body, per-phase remaining-time clamps, an armed watchdog and the cancel flag.
     * Copies the 200 body into {@code sink}, capped at {@code maxBytes}.
     */
    private FetchResult<Long> run(String url, long maxBytes, OutputStream sink) {
        // Register the request BEFORE waiting on the semaphore, so cancel()/shutdown() reach work that is still queued;
        // a request cancelled while queued is rejected after admission and never opens a connection. Registration is
        // coordinated with the terminal `closed` flag under lifecycleLock: once closed, a new request is refused here
        // and never starts I/O — there is no window between the cancellation scan and admission.
        Request request = new Request();
        if (!register(request)) return FetchResult.err("closed");
        try {
            try {
                inFlight.acquire(); // admission control at the common fetch boundary (manifest AND image)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FetchResult.err("interrupted");
            }
            try {
                long deadline = clock.nowMs() + REQUEST_DEADLINE_MS;
                Watchdog.Arm arm;
                // ADMISSION — atomically with the closed/cancelled-check, record the worker thread and arm the
                // per-request ABSOLUTE-DEADLINE task, under the SAME lock shutdown() uses. Because shutdown() never
                // disposes the watchdog (only the reaper does, and only AFTER this request has exited), the armed task
                // is guaranteed to survive shutdown and fire at the deadline — the hard guarantee that a request which
                // enters getResponseCode() after close is still killed. A source already closed here never arms/starts.
                synchronized (lifecycleLock) {
                    if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");
                    request.bind(Thread.currentThread()); // recorded so a cancel/deadline can interrupt this thread
                    try {
                        arm = watchdog.arm(REQUEST_DEADLINE_MS, request::abort);
                    } catch (RejectedExecutionException disposed) {
                        request.abort();
                        return FetchResult.err("closed");
                    }
                }
                try {
                    String current = url;
                    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                        FetchResult<String> allowed = ArtHosts.check(current);
                        if (!allowed.isOk()) return allowed.propagate();
                        long remaining = remaining(deadline);
                        if (remaining <= 0) return FetchResult.err("deadline before hop " + hop);
                        if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");

                        HttpURLConnection c = null;
                        try {
                            c = connections.open(current); // creates the connection object only; no network yet
                            // ATOMIC START GATE — the ONE point where starting a connection and closing the source are
                            // made mutually exclusive, under the SAME lock shutdown() uses. Either this block observes
                            // not-closed/not-cancelled and publishes the connection (commit to start), or it observes a
                            // close/cancel that won the race and bails WITHOUT starting. shutdown() can only run its
                            // close+snapshot either fully before this block (we see closed → never start) or fully after
                            // it (the request is in the snapshot with its connection published → shutdown disconnects it).
                            synchronized (lifecycleLock) {
                                if (closed || request.cancelled.get()) {
                                    return FetchResult.err(closed ? "closed" : "cancelled");
                                }
                                request.live.set(c); // published under the lock; shutdown()'s snapshot now sees it
                            }
                            c.setInstanceFollowRedirects(false);
                            c.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remaining));
                            c.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remaining));
                            c.setRequestProperty("User-Agent", userAgent);

                            int status = c.getResponseCode(); // starts the socket; bounded by the timeouts just set
                            // POST-START OBSERVATION — a shutdown()/cancel() that raced the gate→connect window may have
                            // disconnected a still-unstarted socket (a no-op on Android), losing the abort at startup.
                            // Catch it here, before acting on the response or following a redirect: the response is
                            // discarded and the connection torn down in the finally. `closed` is the SYNCHRONOUS stop
                            // signal (set under the lock by shutdown()), so the worker bails even though the heavy abort
                            // runs asynchronously; the deadline task remains the hard guarantee for a stalled connection.
                            if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");
                            remaining = remaining(deadline);
                            if (remaining <= 0) return FetchResult.err("deadline after headers");
                            if (isRedirect(status)) {
                                FetchResult<String> next = ArtHosts.redirect(current, c.getHeaderField("Location"));
                                if (!next.isOk()) return next.propagate();
                                current = next.value();
                                continue;
                            }
                            if (status != 200) return FetchResult.err("HTTP " + status + " for " + current);
                            long length = c.getContentLengthLong();
                            if (length > maxBytes) return FetchResult.err("too large: " + length);
                            c.setReadTimeout((int) Math.max(1, Math.min(READ_TIMEOUT_MS, remaining)));
                            try (InputStream in = c.getInputStream()) {
                                return copyCapped(in, sink, maxBytes, deadline, request);
                            }
                        } finally {
                            request.live.set(null);
                            if (c != null) c.disconnect();
                        }
                    }
                    return FetchResult.err("too many redirects");
                } catch (IOException | RuntimeException e) {
                    return FetchResult.err(request.cancelled.get() ? "cancelled" : String.valueOf(e));
                } finally {
                    arm.disarm();
                }
            } finally {
                inFlight.release();
            }
        } finally {
            // Retire FIRST (serialized with abort() under the request lock): mark retired, null the thread ref, and
            // consume any race-delivered interrupt — so no interrupt leaks into this pooled thread's next task. Then
            // unregister (wakes the reaper). After retire() no abort() can interrupt this thread.
            request.retire();
            unregister(request);
        }
    }

    /** Number of requests currently registered (queued or running); for tests. */
    int activeCount() {
        return active.size();
    }

    /**
     * Copies at most {@code maxBytes} into {@code sink}, checking the cancel flag and the deadline between chunks (the
     * cooperative fast path; a blocked {@code read()} is broken by the watchdog/cancel disconnect). ok(bytes copied),
     * or err on cap overflow, deadline or cancellation.
     */
    private FetchResult<Long> copyCapped(InputStream in, OutputStream sink, long maxBytes, long deadline, Request request)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (true) {
            // Check BEFORE every read AND before returning success, so an EOF that coincides with a close/cancel or an
            // expired deadline can never be reported as a successful fetch. `closed` is the synchronous stop signal.
            if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");
            if (remaining(deadline) <= 0) return FetchResult.err("deadline during body");
            int n = in.read(buf);
            if (n < 0) break; // clean EOF
            if (n == 0) continue;
            total += n;
            if (total > maxBytes) return FetchResult.err("exceeds " + maxBytes + " bytes");
            sink.write(buf, 0, n);
        }
        if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");
        if (remaining(deadline) <= 0) return FetchResult.err("deadline during body");
        return FetchResult.ok(total);
    }

    private long remaining(long deadline) {
        return deadline - clock.nowMs();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * One request's cancellation state, worker thread and current connection (abortable from another thread).
     *
     * <p>Interrupt delivery and retirement are serialized under a SINGLE per-request mutex ({@link #retireLock}): a
     * cancel/deadline callback interrupts the worker ONLY while the request is still active, and the worker's own exit
     * retires the request and consumes any race-delivered interrupt — both under that one lock. This guarantees an
     * interrupt can never land on the pooled worker thread's NEXT task after this request has retired.
     */
    private static final class Request {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<HttpURLConnection> live = new AtomicReference<>();
        /** The single mutex serializing interrupt delivery ({@link #abort()}) with retirement ({@link #retire()}). */
        private final Object retireLock = new Object();
        private Thread worker;   // guarded by retireLock
        private boolean retired; // guarded by retireLock

        /** Records the worker thread at admission (before any I/O), so a later abort can target exactly this thread. */
        void bind(Thread w) {
            synchronized (retireLock) {
                worker = w;
            }
        }

        /**
         * Effective cancellation from another thread (the deadline task, {@link #cancel()} or {@link #shutdown()}):
         * set the flag, deliver the retirement-guarded INTERRUPT FIRST (under {@link #retireLock}, only if NOT yet
         * retired), and ONLY THEN disconnect the live connection OUTSIDE the lock. Interrupt-before-disconnect so a
         * BLOCKING {@code disconnect()} can never stall interrupt delivery — the interrupt is what reliably stops the
         * request. The retired-check-then-interrupt is atomic with {@link #retire()}, so an interrupt never reaches a
         * worker that has moved on to its next pooled task. Both interrupt and disconnect are needed: a
         * disconnect-before-connect is an Android no-op, so the deadline task fires abort() again once the connection
         * is started. Idempotent; any thread.
         */
        void abort() {
            cancelled.set(true);
            synchronized (retireLock) {
                if (!retired && worker != null) worker.interrupt();
            }
            HttpURLConnection c = live.get();
            if (c != null) c.disconnect();
        }

        /**
         * Called by the worker on request EXIT, under the SAME lock {@link #abort()} uses: mark retired, null the
         * thread reference, and CLEAR this thread's interrupt status after retiring — so an interrupt a concurrent
         * abort() may have just delivered is consumed here and never leaks into the pooled thread's next task.
         */
        void retire() {
            synchronized (retireLock) {
                retired = true;
                worker = null;
                Thread.interrupted(); // consume a race-delivered interrupt, under the lock
            }
        }
    }

    private static Watchdog realWatchdog() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "art-watchdog");
            t.setDaemon(true);
            return t;
        });
        return new Watchdog() {
            @Override public Arm arm(long delayMs, Runnable onFire) {
                // Throws RejectedExecutionException if the executor was disposed by a concurrent shutdown(); the fetch
                // loop ({@link #run}) treats that as a terminal "closed" and refuses to start unguarded I/O.
                ScheduledFuture<?> f = executor.schedule(onFire, delayMs, TimeUnit.MILLISECONDS);
                return () -> f.cancel(false);
            }

            @Override public void shutdown() {
                executor.shutdownNow();
            }
        };
    }
}
