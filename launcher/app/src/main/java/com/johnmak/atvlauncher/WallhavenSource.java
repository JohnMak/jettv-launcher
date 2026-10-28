package com.johnmak.atvlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * The runtime Wallhaven boundary — the successor to the retired {@code ArtSource} for the ART variant (plan B1/step 2).
 * It builds the keyless search URL, performs the guarded fetch (the {@link UpdateHosts} pattern: manual
 * redirects, per-hop {@link WallhavenHosts} re-check, one monotonic deadline, watchdog + cancel, at most two in-flight)
 * and surfaces the STRUCTURED HTTP status (so the recovery layer can honour {@code 429 Retry-After}). ZERO disk writes
 * for images: a body is read into a single pre-sized bounded {@code byte[]} {@link Handle} in memory (no disk cache).
 *
 * <p>This is a self-contained copy of the retired {@code ArtSource}'s brain-reviewed teardown/cancellation design of
 * record (the project keeps independent copies of this fetch pattern in {@link ApkDownloader}/{@link UpdateHosts}).
 * The only departures from that pattern are the BODY handling (a bounded {@code byte[]} with exact/absent
 * {@code Content-Length} handling, replacing the growing {@code ByteArrayOutputStream}), the structured status + the
 * {@code image/*} {@code Content-Type} early reject (image fetches only), and {@link OutOfMemoryError} caught around
 * the allocating read phase.
 *
 * <p>No NSFW surface EVER: {@link #searchUrl} hard-codes {@code categories=100} (General) + {@code purity=100} (SFW);
 * a preset may only append a {@code q=} tag, never widen category/purity.
 */
final class WallhavenSource {
    private static final int MAX_REDIRECTS = 5;
    static final long REQUEST_DEADLINE_MS = 30_000;
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_IN_FLIGHT = 2;
    static final long REAPER_GRACE_MS = 2_000;
    private static final long REAPER_POLL_MS = 50;
    private static final long NO_GRACE = -1L;

    /** Hard cap on the raw image body read into memory (the pre-sized bounded {@code byte[]} handle); ~20 MB. With
     * {@code POOL_SIZE=2} this bounds raw live bytes at ≤2×CAP ≈40 MB. */
    static final long IMAGE_MAX_BYTES = 20L * 1024 * 1024;

    private static final String DEFAULT_PARAMS =
            "categories=100&purity=100&atleast=3840x2160&ratios=16x9&sorting=random";

    /** A bounded in-memory body: the backing {@code buffer} (possibly larger than the body, in the absent-length case)
     * and the EXACT number of valid bytes. Decode/parse MUST use {@code (buffer, 0, bytesRead)} — never {@code buffer.length}. */
    static final class Handle {
        final byte[] buffer;
        final int bytesRead;

        Handle(byte[] buffer, int bytesRead) {
            this.buffer = buffer;
            this.bytesRead = bytesRead;
        }
    }

    /** The structured outcome of a non-200 HTTP response, carried as a {@link FetchResult} error kind so the recovery
     * layer can branch (429 → {@code Retry-After} reschedule). {@code retryAfterMs} is {@code -1} when absent/unparsable. */
    static final class HttpStatus {
        final int code;
        final long retryAfterMs;

        HttpStatus(int code, long retryAfterMs) {
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** Opens a connection for a URL; the single network seam. */
    interface Connections {
        HttpURLConnection open(String url) throws IOException;
    }

    /** A monotonic clock in milliseconds. */
    interface Clock {
        long nowMs();
    }

    /** Schedules {@code onFire} once after {@code delayMs}; the handle cancels it if the fetch finishes first. */
    interface Watchdog {
        Arm arm(long delayMs, Runnable onFire);

        default void shutdown() {}

        interface Arm {
            void disarm();
        }
    }

    /** Polled between body chunks; returns an error label to abort (cancel/close/deadline), or null to continue. */
    interface AbortCheck {
        String poll();
    }

    private final Connections connections;
    private final Clock clock;
    private final Watchdog watchdog;
    private final String userAgent;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;
    private final java.util.Set<Request> active = ConcurrentHashMap.newKeySet();

    /** Production wiring: real network, a monotonic clock, a daemon watchdog thread. */
    WallhavenSource(String userAgent) {
        this(userAgent,
                url -> (HttpURLConnection) new URL(url).openConnection(),
                () -> System.nanoTime() / 1_000_000L,
                realWatchdog());
    }

    WallhavenSource(String userAgent, Connections connections, Clock clock, Watchdog watchdog) {
        this.userAgent = userAgent;
        this.connections = connections;
        this.clock = clock;
        this.watchdog = watchdog;
    }

    // ---- public API ---------------------------------------------------------------------------

    /**
     * The keyless search URL for a page: fixed SFW/General defaults + {@code &page=N} + (when non-empty) {@code &seed=…}
     * (Wallhaven's random-pagination continuity token) + (when non-empty) {@code &q=…} (the preset tag), both encoded.
     * Always on {@link WallhavenHosts#API_HOST}; always passes {@link WallhavenHosts#check}.
     */
    static String searchUrl(String q, String seed, int page) {
        StringBuilder sb = new StringBuilder(WallhavenHosts.API_BASE)
                .append("/api/v1/search?")
                .append(DEFAULT_PARAMS)
                .append("&page=").append(Math.max(1, page));
        if (seed != null && !seed.isEmpty()) sb.append("&seed=").append(encode(seed));
        if (q != null && !q.isEmpty()) sb.append("&q=").append(encode(q));
        return sb.toString();
    }

    /** Fetches and parses a search page. err may carry an {@link HttpStatus} kind (e.g. 429 with {@code Retry-After}). */
    FetchResult<WallhavenSearch.Page> search(String q, String seed, int page) {
        FetchResult<Handle> h = fetch(searchUrl(q, seed, page), WallhavenSearch.JSON_MAX_BYTES, false);
        if (!h.isOk()) return h.propagate();
        Handle body = h.value();
        try {
            return WallhavenSearch.parse(Arrays.copyOf(body.buffer, body.bytesRead));
        } catch (OutOfMemoryError oom) {
            return FetchResult.err("search copy oom");
        }
    }

    /** Fetches the full-resolution image bytes into a bounded in-memory handle; no decode, no disk. err = unavailable
     * (may carry an {@link HttpStatus} kind). The image's {@code path} host is re-validated by the per-hop check. */
    FetchResult<Handle> image(WallhavenImage img) {
        return fetch(img.path, IMAGE_MAX_BYTES, true);
    }

    void cancel() {
        abortSnapshot();
    }

    void shutdown() {
        shutdownWithin(NO_GRACE);
    }

    /** Bounded retire-abort grace: stop admitting work, then wait up to {@code graceMs} for in-flight requests. */
    void shutdownWithin(long graceMs) {
        List<Request> toAbort;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            toAbort = new ArrayList<>(active);
        }
        Watchdog.Arm graceArm = null;
        if (graceMs >= 0) {
            try {
                graceArm = watchdog.arm(graceMs, () -> {
                    for (Request r : toAbort) r.signalAndInterrupt();
                    Thread disconnector = new Thread(() -> {
                        for (Request r : toAbort) r.disconnectLive();
                    }, "wallhaven-grace-disconnect");
                    disconnector.setDaemon(true);
                    disconnector.start();
                });
            } catch (RejectedExecutionException alreadyDisposed) {
                graceArm = null;
            }
        }
        final Watchdog.Arm grace = graceArm;
        ExecutorService teardown = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "wallhaven-teardown");
            t.setDaemon(true);
            return t;
        });
        teardown.execute(() -> {
            awaitDrain(REQUEST_DEADLINE_MS + REAPER_GRACE_MS);
            if (grace != null) grace.disarm();
            watchdog.shutdown();
        });
        teardown.execute(() -> {
            for (Request r : toAbort) r.abort();
        });
        teardown.shutdown();
    }

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
            lifecycleLock.notifyAll();
        }
    }

    private void abortSnapshot() {
        List<Request> snapshot;
        synchronized (lifecycleLock) {
            snapshot = new ArrayList<>(active);
        }
        for (Request r : snapshot) r.abort();
    }

    int activeCount() {
        return active.size();
    }

    // ---- guarded fetch loop -------------------------------------------------------------------

    /**
     * The production fetch loop: manual redirects (≤{@link #MAX_REDIRECTS}), one
     * monotonic deadline covering hops + connect + headers + body, per-phase remaining-time clamps, an armed watchdog
     * and the cancel flag. On a 200 it reads the body into a bounded {@link Handle}; on a non-200 it returns an error
     * carrying the structured {@link HttpStatus}.
     */
    private FetchResult<Handle> fetch(String url, long maxBytes, boolean image) {
        Request request = new Request();
        if (!register(request)) return FetchResult.err("closed");
        try {
            try {
                inFlight.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FetchResult.err("interrupted");
            }
            try {
                long deadline = clock.nowMs() + REQUEST_DEADLINE_MS;
                Watchdog.Arm arm;
                synchronized (lifecycleLock) {
                    if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");
                    request.bind(Thread.currentThread());
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
                        FetchResult<String> allowed = WallhavenHosts.check(current);
                        if (!allowed.isOk()) return allowed.propagate();
                        long remaining = remaining(deadline);
                        if (remaining <= 0) return FetchResult.err("deadline before hop " + hop);
                        if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");

                        HttpURLConnection c = null;
                        try {
                            c = connections.open(current);
                            synchronized (lifecycleLock) {
                                if (closed || request.cancelled.get()) {
                                    return FetchResult.err(closed ? "closed" : "cancelled");
                                }
                                request.live.set(c);
                            }
                            c.setInstanceFollowRedirects(false);
                            c.setConnectTimeout((int) Math.min(CONNECT_TIMEOUT_MS, remaining));
                            c.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remaining));
                            c.setRequestProperty("User-Agent", userAgent);

                            int status = c.getResponseCode();
                            if (closed || request.cancelled.get()) return FetchResult.err(closed ? "closed" : "cancelled");
                            remaining = remaining(deadline);
                            if (remaining <= 0) return FetchResult.err("deadline after headers");
                            if (isRedirect(status)) {
                                FetchResult<String> next = WallhavenHosts.redirect(current, c.getHeaderField("Location"));
                                if (!next.isOk()) return next.propagate();
                                current = next.value();
                                continue;
                            }
                            if (status != 200) {
                                long retryAfter = status == 429 ? parseRetryAfterMs(c.getHeaderField("Retry-After")) : -1;
                                return FetchResult.err("HTTP " + status + " for " + current,
                                        new HttpStatus(status, retryAfter));
                            }
                            if (image && !isImageContentType(c.getContentType())) {
                                return FetchResult.err("not an image: " + c.getContentType());
                            }
                            long length = c.getContentLengthLong();
                            if (length > maxBytes) return FetchResult.err("too large: " + length);
                            c.setReadTimeout((int) Math.max(1, Math.min(READ_TIMEOUT_MS, remaining)));
                            final long dl = deadline;
                            final Request req = request;
                            try (InputStream in = c.getInputStream()) {
                                return readBounded(in, length, maxBytes, () -> {
                                    if (closed || req.cancelled.get()) return closed ? "closed" : "cancelled";
                                    if (remaining(dl) <= 0) return "deadline during body";
                                    return null;
                                });
                            }
                        } finally {
                            request.live.set(null);
                            if (c != null) c.disconnect();
                        }
                    }
                    return FetchResult.err("too many redirects");
                } catch (IOException | RuntimeException e) {
                    return FetchResult.err(request.cancelled.get() ? "cancelled" : String.valueOf(e));
                } catch (OutOfMemoryError oom) {
                    return FetchResult.err("fetch oom");
                } finally {
                    arm.disarm();
                }
            } finally {
                inFlight.release();
            }
        } finally {
            request.retire();
            unregister(request);
        }
    }

    /**
     * Reads the 200 body into a bounded {@link Handle}, checking cancel/deadline between chunks.
     * <ul>
     *   <li><b>{@code Content-Length} present (≥0) and ≤ cap:</b> allocate {@code byte[length]} and fill it EXACTLY;
     *       fewer bytes (EOF early) → truncated; any extra byte past {@code length} → oversized. Either mismatch is an
     *       item failure, never a silent accept.</li>
     *   <li><b>{@code Content-Length} absent (&lt;0):</b> allocate {@code byte[cap]} and read with a HARD overflow
     *       check — filling the buffer alone does NOT prove the body fit; one further read after the buffer is full
     *       must hit EOF, otherwise the body exceeds the cap → oversized.</li>
     * </ul>
     */
    static FetchResult<Handle> readBounded(InputStream in, long length, long maxBytes, AbortCheck abort)
            throws IOException {
        if (length > maxBytes) return FetchResult.err("too large: " + length); // defensive (also checked by caller)
        byte[] buffer;
        try {
            buffer = new byte[(int) (length >= 0 ? length : maxBytes)];
        } catch (OutOfMemoryError oom) {
            return FetchResult.err("read alloc oom");
        }
        int total = 0;
        while (total < buffer.length) {
            String aborted = abort.poll();
            if (aborted != null) return FetchResult.err(aborted);
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) break; // EOF
            total += n;
        }
        String aborted = abort.poll();
        if (aborted != null) return FetchResult.err(aborted);
        // The buffer is now full (total == buffer.length) OR the stream hit EOF early (total < buffer.length). The
        // overflow/EOF probe below is itself a blocking read, so RE-CHECK the abort immediately before reporting
        // success — otherwise a cancel/deadline that lands during the probe would be wrongly reported as ok
        // (preserving the post-EOF cancellation invariant of this fetch pattern).
        if (length >= 0) {
            if (total < buffer.length) return FetchResult.err("truncated: " + total + " < " + length);
            if (in.read() >= 0) return FetchResult.err("oversized: more than advertised " + length);
            String afterProbe = abort.poll();
            if (afterProbe != null) return FetchResult.err(afterProbe);
            return FetchResult.ok(new Handle(buffer, total));
        }
        // absent length: if we filled the cap buffer, one more read must be EOF or the body exceeds the cap
        if (total == buffer.length && in.read() >= 0) {
            return FetchResult.err("oversized: exceeds cap " + maxBytes);
        }
        String afterProbe = abort.poll();
        if (afterProbe != null) return FetchResult.err(afterProbe);
        return FetchResult.ok(new Handle(buffer, total));
    }

    private long remaining(long deadline) {
        return deadline - clock.nowMs();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** True when a {@code Content-Type} is an image type (advisory early reject; {@code outMimeType} is authoritative). */
    static boolean isImageContentType(String contentType) {
        if (contentType == null) return false;
        String t = contentType.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("image/");
    }

    /** Parses a {@code Retry-After} header given as DELTA-SECONDS into milliseconds; {@code -1} when absent or not a
     * plain non-negative integer (an HTTP-date form falls back to the caller's default backoff — no wall-clock use). */
    static long parseRetryAfterMs(String retryAfter) {
        if (retryAfter == null) return -1;
        String s = retryAfter.trim();
        if (s.isEmpty()) return -1;
        try {
            long seconds = Long.parseLong(s);
            return seconds < 0 ? -1 : seconds * 1000L;
        } catch (NumberFormatException notSeconds) {
            return -1;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("UTF-8 always supported", e);
        }
    }

    /** One request's cancellation state, worker thread and current connection. */
    private static final class Request {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<HttpURLConnection> live = new AtomicReference<>();
        private final Object retireLock = new Object();
        private Thread worker;
        private boolean retired;

        void bind(Thread w) {
            synchronized (retireLock) {
                worker = w;
            }
        }

        void abort() {
            signalAndInterrupt();
            disconnectLive();
        }

        void signalAndInterrupt() {
            cancelled.set(true);
            synchronized (retireLock) {
                if (!retired && worker != null) worker.interrupt();
            }
        }

        void disconnectLive() {
            HttpURLConnection c = live.get();
            if (c != null) c.disconnect();
        }

        void retire() {
            synchronized (retireLock) {
                retired = true;
                worker = null;
                Thread.interrupted();
            }
        }
    }

    private static Watchdog realWatchdog() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wallhaven-watchdog");
            t.setDaemon(true);
            return t;
        });
        return new Watchdog() {
            @Override public Arm arm(long delayMs, Runnable onFire) {
                ScheduledFuture<?> f = executor.schedule(onFire, delayMs, TimeUnit.MILLISECONDS);
                return () -> f.cancel(false);
            }

            @Override public void shutdown() {
                executor.shutdownNow();
            }
        };
    }
}
