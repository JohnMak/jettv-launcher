package com.johnmak.atvlauncher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * {@link Watchdog} and {@link #cancel()} each abort blocked I/O from outside the fetch thread by disconnecting the
 * live connection (the between-chunks flag is the cooperative fast path); at most two in-flight fetches. The manifest
 * is served from the on-disk last-valid copy and replaced ONLY by a validated candidate.
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
    /** The connections of requests still running, so {@link #cancel()} can abort each from the outside. */
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

    /** Aborts every in-flight fetch: sets the cancel flag and disconnects the live connections from the outside. */
    void cancel() {
        for (Request r : active) r.abort();
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
        // Register the request BEFORE waiting on the semaphore, so cancel() reaches work that is still queued; a
        // request cancelled while queued is rejected after admission and never opens a connection.
        Request request = new Request();
        active.add(request);
        try {
            try {
                inFlight.acquire(); // admission control at the common fetch boundary (manifest AND image)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FetchResult.err("interrupted");
            }
            try {
                if (request.cancelled.get()) return FetchResult.err("cancelled"); // cancelled while queued → no connect
                long deadline = clock.nowMs() + REQUEST_DEADLINE_MS;
                Watchdog.Arm arm = watchdog.arm(REQUEST_DEADLINE_MS, request::abort);
                try {
                    String current = url;
                    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                        FetchResult<String> allowed = ArtHosts.check(current);
                        if (!allowed.isOk()) return allowed.propagate();
                        long remaining = remaining(deadline);
                        if (remaining <= 0) return FetchResult.err("deadline before hop " + hop);
                        if (request.cancelled.get()) return FetchResult.err("cancelled");

                        HttpURLConnection c = null;
                        try {
                            c = connections.open(current);
                            request.live.set(c);
                            if (request.cancelled.get()) return FetchResult.err("cancelled");
                            c.setInstanceFollowRedirects(false);
                            c.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remaining));
                            c.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remaining));
                            c.setRequestProperty("User-Agent", userAgent);

                            int status = c.getResponseCode();
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
            active.remove(request);
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
            // Check BEFORE every read AND before returning success, so an EOF that coincides with a cancel or an
            // expired deadline can never be reported as a successful fetch.
            if (request.cancelled.get()) return FetchResult.err("cancelled");
            if (remaining(deadline) <= 0) return FetchResult.err("deadline during body");
            int n = in.read(buf);
            if (n < 0) break; // clean EOF
            if (n == 0) continue;
            total += n;
            if (total > maxBytes) return FetchResult.err("exceeds " + maxBytes + " bytes");
            sink.write(buf, 0, n);
        }
        if (request.cancelled.get()) return FetchResult.err("cancelled");
        if (remaining(deadline) <= 0) return FetchResult.err("deadline during body");
        return FetchResult.ok(total);
    }

    private long remaining(long deadline) {
        return deadline - clock.nowMs();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** One request's cancellation state and its current connection (abortable from another thread). */
    private static final class Request {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<HttpURLConnection> live = new AtomicReference<>();

        void abort() {
            cancelled.set(true);
            HttpURLConnection c = live.get();
            if (c != null) c.disconnect();
        }
    }

    private static Watchdog realWatchdog() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "art-watchdog");
            t.setDaemon(true);
            return t;
        });
        return (delayMs, onFire) -> {
            ScheduledFuture<?> f = executor.schedule(onFire, delayMs, TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        };
    }
}
