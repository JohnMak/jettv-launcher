package com.johnmak.atvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ART screensaver layer: a slow fade-through-black slideshow of 4K+ Wallhaven wallpapers streamed keylessly from
 * {@link WallhavenSource}, decoded IN MEMORY (no disk cache) at the panel's resolution, over a black letterbox. The
 * starfield shows beneath it until the first image is ready and whenever the stream is loading / recovering / has
 * given up (the owner crossfades this whole layer's alpha on {@link PhaseListener#onPhase}).
 *
 * <p>Threading / memory contract (M3, unchanged): the ONLY place state is mutated is the MAIN thread — the background
 * pool does nothing but the blocking {@link Session#page}/{@link Session#fetch}/{@link Session#decode} and then posts
 * its result back to main, so {@link Slideshow} needs no locks. The main thread captures the immutable decode job
 * {@code (viewWidth, viewHeight, epoch)} at schedule time; the worker receives only those values and never touches a
 * View. Decode sizing waits for nonzero bounds (the Dream prepares before layout), re-triggering on the first nonzero
 * layout. Release order on replace/teardown: cancel timers/animators → clear the {@link ImageView} drawable
 * reference → recycle the bitmap (and release its budget permit).
 *
 * <p><b>Unified bounded model (Model B, unchanged)</b> — a stopped session NEVER delays its replacement, yet threads
 * AND bitmaps stay hard-bounded across active + retiring sessions: ONE shared fixed pool (size 2); a shared bitmap
 * budget {@code Semaphore(2)} acquired on the worker AFTER the fetch and BEFORE the bitmap allocation and released on
 * recycle; session-scoped, admission-atomic cancellation ({@link WallhavenSource#shutdownWithin} with a short retire
 * grace). The raw image {@code byte[]} is held only until {@code decodeByteArray} returns, then released.
 *
 * <p><b>Streaming (plan B5).</b> On the happy path the queue is a bounded 2-page sliding window ({@link WallhavenPager});
 * pages are fetched one-ahead carrying the seed and discarded once consumed; the seed renews at end-of-results
 * (per-seed no-repeat).
 *
 * <p><b>Recovery — the SIMPLE clean-restart model (plan B6, owner-approved).</b> There is ONE error path, no per-piece
 * salvage: on ANY error (page 429, image/CDN 429, network/decode failure, or a valid-but-empty page) the coordinator
 * FIRST extends the single monotonic {@code cooldownDeadline} ({@code max(current, now + Retry-After-or-backoff)}, never
 * shortened), then DISCARDS all in-flight work atomically (bumps {@code seedGeneration} so every in-flight page/image/
 * decode is dropped on arrival; clears {@code decodeInFlight}/{@code pageFetchInFlight}; drops a prefetched pending
 * bitmap). When the deadline passes a single {@code cooldownTick} performs a CLEAN RESTART: a brand-new
 * {@link WallhavenPager} (a fresh random seed at page 1). Recovery from {@link ArtPlayback.Phase#EXHAUSTED} is that very
 * same clean restart (EXHAUSTED is non-terminal — it only signals a persistent-failure run). The currently-SHOWN image
 * is kept across a transient error; the visual starfield fallback is automatic (the 2s black-hold → {@code
 * ScreensaverKind} non-RENDERING→STARFIELD) during the wait. {@code screensaverType} is never mutated.
 */
final class ArtScreensaverView extends FlatFrameLayout {
    /** At most this many decoded bitmaps live at once, across the active and any retiring session (GLOBAL). */
    static final int MAX_LIVE_BITMAPS = 2;
    /** Shared background threads (GLOBAL — one pool owned by {@code Screensaver}). */
    static final int POOL_SIZE = 2;
    static final long POOL_KEEPALIVE_MS = 10_000;
    /** On RETIRE a session's in-flight request is aborted within ~this long (not the 30s active deadline). */
    static final long RETIRE_ABORT_GRACE_MS = 1_500;
    static final long FADE_OUT_MS = 300;
    static final long FADE_IN_MS = 300;
    /** If the next image is still not ready this long after reaching black, reveal the starfield (tasteful fallback). */
    static final long BLACK_HOLD_MAX_MS = 2_000;

    // ---- retry/recovery policy (plan B6; SIMPLE clean-restart model — tuning numbers, reversible) -------------
    /** Consecutive errors with no decoded READY before the persistent-failure signal (EXHAUSTED/starfield) is emitted;
     * the clean restart keeps running regardless (EXHAUSTED is non-terminal). */
    static final int MAX_ERROR_STREAK = 6;
    /** Backoff for a clean restart after an error: {@code RETRY_BASE_MS << min(errorStreak, 5)} capped at
     * {@link #RETRY_MAX_MS} (used when there is no {@code Retry-After}). */
    static final long RETRY_BASE_MS = 2_000;
    static final long RETRY_MAX_MS = 60_000;
    /** Backoff used for a 429 when no (or an unparsable) {@code Retry-After} is given. */
    static final long DEFAULT_429_BACKOFF_MS = 30_000;

    /** Reports the slideshow phase so the owner ({@code Screensaver}) can crossfade this whole layer in/out. */
    interface PhaseListener {
        void onPhase(ArtPlayback.Phase phase);
    }

    // ---- injected seams (production wiring below; fakes in the test) --------------------------

    /** Opens fetch sessions. One session per {@link Slideshow#start()}; each has its own isolated fetch scope. */
    interface Pipeline<B> {
        Session<B> open();
    }

    /** One fetch session. All three blocking methods run on a pool thread only. {@code fetch} and {@code decode} are
     * SPLIT so the coordinator can take the shared bitmap-budget permit between them (after I/O, before allocation). */
    interface Session<B> {
        /** Fetch+parse one search page. {@code seed==""} with {@code page==1} requests a FRESH random seed. err may
         * carry a {@link WallhavenSource.HttpStatus} kind (e.g. 429 with {@code Retry-After}). Holds NO bitmap budget. */
        FetchResult<WallhavenSearch.Page> page(String seed, int page);

        /** Fetch the full-resolution image bytes for {@code image} into an opaque in-memory handle. ok(handle) = ready
         * to decode; err = unavailable, and may carry a {@link WallhavenSource.HttpStatus} kind (e.g. a CDN 429 with
         * {@code Retry-After}) so the coordinator can cooldown-retry rather than consume the descriptor. Holds NO bitmap
         * budget — a stuck fetch never pins decoded memory. Bounded by the session's request deadline. */
        FetchResult<Object> fetch(WallhavenImage image);

        /** Allocate+decode the bitmap from a {@code fetched} handle at the captured display size; null = unavailable
         * (corrupt/oversized/over-budget source, wrong format, or decode failure). The caller already holds one permit. */
        B decode(Object fetched, int targetW, int targetH);

        /** Cancel THIS session: SYNCHRONOUSLY refuse further admission and abort in-flight I/O. Non-blocking; idempotent. */
        void cancel();
    }

    /** The main-thread rendering surface: two layers + a shared caption over a black letterbox. Transitions go THROUGH
     * BLACK — the two layers are never both visible at once. */
    interface Stage<B> {
        void fadeOutToBlack(int layer, long ms, Runnable onEnd);

        void fadeInFromBlack(int layer, B bitmap, String caption, long ms, Runnable onEnd);

        void clear(int layer);

        void recycle(B bitmap);

        void cancelAnimations();

        int[] boundsPx();
    }

    /** Posts work onto the main thread. {@link #post} returns whether the runnable was accepted (false when the looper
     * is dead), so the caller can clean up a bitmap handoff that will never be delivered. {@link #postDelayed} keys the
     * runnable by identity (distinct timers: display, black-hold, cooldown, recover). */
    interface Main {
        boolean post(Runnable r);

        void postDelayed(Runnable r, long delayMs);

        void cancel(Runnable r);
    }

    /** A monotonic clock in ms — the SAME timebase the {@link Main} handler schedules against (production:
     * {@code SystemClock.uptimeMillis}). Used for the single absolute cooldown deadline (plan B6). */
    interface Clock {
        long nowMs();
    }

    // ---- the pure coordinator (the hard part; unit-tested offline) ----------------------------

    /**
     * The slideshow state machine. Pure (no Android types), single-threaded (every method here runs on {@code main}).
     * The shared {@code worker} pool and {@code bitmapBudget} semaphore span sessions; each session owns its own
     * {@link Session}. Every session has an atomic {@code epoch} (worker pre-check + stale-result discard). The stream
     * is driven by a {@link WallhavenPager}; failures/retries are bounded by attempt caps (plan B6).
     */
    static final class Slideshow<B> {
        private final Pipeline<B> pipeline;
        private final Stage<B> stage;
        private final Executor worker;
        private final Semaphore bitmapBudget; // shared across active + retiring sessions; cap = MAX_LIVE_BITMAPS
        private final Main main;
        private final Clock clock;
        private final PhaseListener listener;
        private final Runnable displayTick = this::onDisplayElapsed;
        private final Runnable blackHoldTick = this::onBlackHoldTimeout;
        /** Fires at the cooldown deadline and performs the CLEAN RESTART (fresh seed) — the ONE recovery path. */
        private final Runnable cooldownTick = this::onCooldownWake;

        private enum Trans { NONE, FADING_OUT, BLACK, FADING_IN }

        /** Session lifecycle token (start/stop/teardown): guards the main-thread transition callbacks. */
        private final AtomicInteger epoch = new AtomicInteger();
        /**
         * The ONE structural invariant pair (plan B6, Codex round-2). {@code seedGeneration} is bumped on every session
         * (re)start/stop AND on every error-discard / clean restart; EVERY background work unit (page request, image
         * fetch, decode) captures it at creation and is dropped on completion if it no longer matches — so an old-window
         * decode can never consume the new window, and a late READY from a stale generation is ignored.
         */
        private final AtomicInteger seedGeneration = new AtomicInteger();
        private Session<B> currentSession;
        private boolean running;
        private boolean tornDown;
        private boolean decodeInFlight;
        private boolean pageFetchInFlight;
        /**
         * The single monotonic cooldown deadline (absolute {@link Clock} ms) — the ONLY admission gate for BOTH page
         * and image requests (plan B6). Extended only via {@code max(current, now + delay)} on any 429/backoff; NEVER
         * reset or shortened by a descriptor change, page change or window exhaustion (only a fresh session resets it).
         * Every admission point parks while {@code now < cooldownDeadline}; {@link #cooldownTick} wakes at the deadline.
         */
        private long cooldownDeadline;
        private Trans trans = Trans.NONE;
        private boolean artHidden = true;

        private WallhavenPager pager;
        private int errorStreak;     // consecutive errors of ANY kind (reset only on a decoded READY)
        private ArtPlayback.Phase phase = ArtPlayback.Phase.LOADING;

        private int curLayer;
        private B curBitmap;         // the current image's bitmap (null while black / starfield); holds one permit
        private B pendingBitmap;     // a decoded next image waiting to fade in; holds one permit
        private String pendingCaption;

        Slideshow(Pipeline<B> pipeline, Stage<B> stage, Executor worker, Semaphore bitmapBudget, Main main,
                Clock clock, PhaseListener listener) {
            this.pipeline = pipeline;
            this.stage = stage;
            this.worker = worker;
            this.bitmapBudget = bitmapBudget;
            this.main = main;
            this.clock = clock;
            this.listener = listener;
        }

        /** Begins (or restarts) a session: opens a fresh {@link Session}, bumps the epoch, shows the starfield
         * (LOADING), and kicks off the page-1 fetch on the shared pool. Idempotent while running; refused after teardown. */
        void start() {
            if (running || tornDown) return;
            running = true;
            currentSession = pipeline.open();
            epoch.incrementAndGet();
            seedGeneration.incrementAndGet(); // a fresh session is a new generation — old work units are stale
            decodeInFlight = false;
            pageFetchInFlight = false;
            cooldownDeadline = 0;             // a fresh session starts with no cooldown (per-session monotonic)
            trans = Trans.NONE;
            pendingBitmap = null;
            pendingCaption = null;
            pager = new WallhavenPager();
            errorStreak = 0;
            phase = ArtPlayback.Phase.LOADING;
            artHidden = true;
            listener.onPhase(ArtPlayback.Phase.LOADING); // starfield shows until the first image is ready
            scheduleRefillIfNeeded(); // a fresh pager needs page 1 ("", 1, fresh)
        }

        void stop() {
            if (!running) return;
            quiesce();
        }

        void teardown() {
            if (tornDown) return;
            tornDown = true;
            quiesce();
        }

        /** The view re-triggers this on the first nonzero layout (the Dream prepares before layout). */
        void onBoundsReady() {
            scheduleDecode();
        }

        int epoch() {
            return epoch.get();
        }

        boolean isRunning() {
            return running;
        }

        private void quiesce() {
            running = false;
            Session<B> ending = currentSession;
            currentSession = null;
            epoch.incrementAndGet();          // invalidate in-flight transition callbacks
            seedGeneration.incrementAndGet(); // invalidate every in-flight fetch/decode work unit
            main.cancel(displayTick);
            main.cancel(blackHoldTick);
            main.cancel(cooldownTick);
            stage.cancelAnimations();
            if (ending != null) ending.cancel();
            trans = Trans.NONE;
            pageFetchInFlight = false;
            cooldownDeadline = 0;
            releaseBitmaps();
        }

        private void releaseBitmaps() {
            if (curBitmap != null) {
                stage.clear(curLayer);
                recycleBitmap(curBitmap);
                curBitmap = null;
            }
            if (pendingBitmap != null) {
                recycleBitmap(pendingBitmap);
                pendingBitmap = null;
            }
        }

        private void recycleBitmap(B bitmap) {
            stage.recycle(bitmap);
            bitmapBudget.release();
        }

        // ---- page streaming (plan B5) ---------------------------------------------------------

        /** Issues a page fetch on the shared pool when the pager wants one and none is already in flight. Used both for
         * the initial page-1 fetch (fresh pager) and for one-ahead refills; never while EXHAUSTED/stopped. */
        private void scheduleRefillIfNeeded() {
            if (!running || tornDown || pageFetchInFlight) return;
            if (inCooldown()) return;          // the ONE monotonic cooldown gates admission (no per-path flag)
            if (phase == ArtPlayback.Phase.EXHAUSTED) return;
            if (currentSession == null) return;
            if (!pager.needsFetch()) return;
            pageFetchInFlight = true;
            final WallhavenPager.Fetch f = pager.plannedFetch();
            final int g = seedGeneration.get(); // captured at creation — checked again on completion
            final Session<B> session = currentSession;
            worker.execute(() -> {
                if (g != seedGeneration.get()) return; // stale generation (stop/replace/seed-renewal) — fetch nothing
                final FetchResult<WallhavenSearch.Page> r = session.page(f.seed, f.page);
                main.post(() -> onPageResult(g, f, r));
            });
        }

        private void onPageResult(int g, WallhavenPager.Fetch planned, FetchResult<WallhavenSearch.Page> result) {
            if (g != seedGeneration.get() || !running) return; // stale generation — drop it
            pageFetchInFlight = false;
            if (result.isOk() && pager != null) {
                // Classify the INCOMING page's usable (post-dedup) descriptors — NOT pager.current(), which could still
                // point at the OLDER page's images and so mask an empty prefetched refill (plan B6: ANY valid-empty page
                // → clean restart, never a silent accept).
                int usable = pager.onPage(result.value(), planned.fresh);
                if (usable > 0) {
                    scheduleDecode();              // decode the current / prefetch the next (errorStreak resets on READY)
                    scheduleRefillIfNeeded();      // keep the one-ahead window filled
                    return;
                }
                onError(backoffMs(errorStreak));   // a valid-but-empty page is an error → clean restart on a fresh seed
                return;
            }
            WallhavenSource.HttpStatus st = result.errorKind(WallhavenSource.HttpStatus.class);
            onError(delayFor(st));
        }

        private boolean inCooldown() {
            return clock.nowMs() < cooldownDeadline;
        }

        /** The ONE error path (plan B6, simple model): FIRST extend the single monotonic cooldown deadline, THEN discard
         * ALL in-flight work. The clean restart happens when {@link #cooldownTick} fires at the deadline. */
        private void onError(long delayMs) {
            extendCooldown(delayMs);               // (1) deadline FIRST — always set before anything else
            discardInFlight();                     // (2) atomically strand every in-flight page/image/decode
            if (++errorStreak >= MAX_ERROR_STREAK && artHidden && phase != ArtPlayback.Phase.EXHAUSTED) {
                // persistent failure with nothing on screen → signal EXHAUSTED (already starfield); the clean restart
                // still runs at the deadline (EXHAUSTED is non-terminal). A currently-shown image is left untouched.
                phase = ArtPlayback.Phase.EXHAUSTED;
                listener.onPhase(ArtPlayback.Phase.EXHAUSTED);
            }
        }

        /** The decode-byte delay for an error: a 429's {@code Retry-After} (or a default), else the backoff schedule. */
        private long delayFor(WallhavenSource.HttpStatus st) {
            if (st != null && st.code == 429) {
                return st.retryAfterMs >= 0 ? st.retryAfterMs : DEFAULT_429_BACKOFF_MS;
            }
            return backoffMs(errorStreak);
        }

        /** Discards ALL in-flight work atomically (plan B6 step 2): bump the generation so every in-flight page/image/
         * decode is dropped on arrival, clear the in-flight flags, and drop any prefetched-but-unshown bitmap. The
         * currently-SHOWN image is deliberately kept (it is not in-flight; the automatic black-hold reveals the
         * starfield once its dwell ends). */
        private void discardInFlight() {
            seedGeneration.incrementAndGet();
            decodeInFlight = false;
            pageFetchInFlight = false;
            if (pendingBitmap != null) {
                recycleBitmap(pendingBitmap);
                pendingBitmap = null;
                pendingCaption = null;
            }
        }

        /** Extends the ONE cooldown deadline monotonically ({@code max(current, now+delay)}) and (re)arms the wake. The
         * deadline is never shortened — a longer {@code Retry-After} is never undercut by a later shorter backoff. */
        private void extendCooldown(long delayMs) {
            long d = Math.max(0, delayMs);
            cooldownDeadline = Math.max(cooldownDeadline, clock.nowMs() + d);
            main.cancel(cooldownTick);
            main.postDelayed(cooldownTick, Math.max(0, cooldownDeadline - clock.nowMs()));
        }

        /** Fires at the cooldown deadline: performs the ONE clean restart (or re-arms if the deadline was extended). */
        private void onCooldownWake() {
            if (!running || tornDown) return;
            if (inCooldown()) {                 // the deadline was extended since this wake was armed — re-arm
                main.cancel(cooldownTick);
                main.postDelayed(cooldownTick, Math.max(0, cooldownDeadline - clock.nowMs()));
                return;
            }
            cleanRestart();
        }

        /** The ONE recovery path (plan B6 step 3): wipe the window and start a brand-new search from a FRESH random seed
         * at page 1. Also the recovery out of EXHAUSTED. No old descriptors or stale in-flight state survive. */
        private void cleanRestart() {
            seedGeneration.incrementAndGet();   // fresh generation for the new search
            pager = new WallhavenPager();        // fresh random seed, page 1
            decodeInFlight = false;
            pageFetchInFlight = false;
            if (phase == ArtPlayback.Phase.EXHAUSTED) phase = ArtPlayback.Phase.LOADING; // leave EXHAUSTED (still starfield)
            scheduleRefillIfNeeded();
            scheduleDecode();
        }

        private static long backoffMs(int attempt) {
            int shift = Math.min(Math.max(0, attempt), 5);
            return Math.min(RETRY_MAX_MS, RETRY_BASE_MS << shift);
        }

        // ---- image decode scheduling ----------------------------------------------------------

        /** Submits a fetch+decode of the pager's current descriptor unless a gate forbids it; when the window is
         * momentarily empty it triggers a page refill instead. */
        private void scheduleDecode() {
            if (!running || tornDown || decodeInFlight) return;
            if (pendingBitmap != null) return;          // the next image is already decoded and waiting
            if (inCooldown()) return;                   // the ONE cooldown gates image admission too (no per-path flag)
            if (phase == ArtPlayback.Phase.EXHAUSTED) return;
            if (currentSession == null || pager == null) return;
            WallhavenImage image = pager.current();
            if (image == null) {                         // nothing buffered — fetch a page, decode resumes on delivery
                scheduleRefillIfNeeded();
                return;
            }
            int[] b = stage.boundsPx();
            if (b[0] <= 0 || b[1] <= 0) return;          // no bounds yet (Dream prepares before layout)
            decodeInFlight = true;
            final Session<B> session = currentSession;
            final int g = seedGeneration.get();
            final int w = b[0];
            final int h = b[1];
            worker.execute(() -> runFetchDecode(session, g, image, w, h));
        }

        /** The pool task: fetch (no permit) → acquire the shared bitmap permit → allocate → post. Pool thread only. A
         * fetch failure carrying a 429 is posted as a STRUCTURED status (no permit) so the coordinator can cooldown-retry
         * the SAME descriptor instead of consuming it. */
        private void runFetchDecode(Session<B> session, int g, WallhavenImage image, int w, int h) {
            // phase 1: fetch (holds NO bitmap permit)
            FetchResult<Object> fetchResult;
            try {
                if (g != seedGeneration.get()) return;        // stale generation before this ran — admit no fetch
                fetchResult = session.fetch(image);           // cancelled session → err; bounded by the retire grace
            } catch (RuntimeException | OutOfMemoryError crash) {
                fetchResult = FetchResult.err("fetch crashed: " + crash);
            }
            if (g != seedGeneration.get()) return;
            if (!fetchResult.isOk()) {
                WallhavenSource.HttpStatus st = fetchResult.errorKind(WallhavenSource.HttpStatus.class);
                final WallhavenSource.HttpStatus cdn429 = (st != null && st.code == 429) ? st : null;
                main.post(() -> onDecode(g, null, cdn429));    // no permit held — nothing to clean up
                return;
            }
            final Object fetched = fetchResult.value();
            // phase 2: acquire the shared bitmap permit, then allocate/decode
            B decoded = null;
            boolean permit = false;
            try {
                bitmapBudget.acquire();                        // AFTER fetch, BEFORE allocation — caps live bitmaps
                permit = true;
                if (g != seedGeneration.get()) return;        // stale while waiting for a permit — do not allocate
                decoded = session.decode(fetched, w, h);       // may return null (unavailable) or throw
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | OutOfMemoryError decodeFailed) {
                // treated as unavailable; the finally releases the permit and still posts (plan B4: catch OOM around
                // the bounds/pixel decode phase too, not just RuntimeException)
            } finally {
                final B result = decoded;
                final boolean hadPermit = permit;
                boolean accepted;
                try {
                    accepted = main.post(() -> onDecode(g, result, null));
                } catch (RuntimeException postRejected) {
                    accepted = false;
                }
                if (!accepted) {
                    if (result != null) stage.recycle(result);
                    if (hadPermit) bitmapBudget.release();
                } else if (result == null && hadPermit) {
                    bitmapBudget.release(); // accepted, but no bitmap — free the reservation (onDecode won't)
                }
            }
        }

        private void onDecode(int g, B bmp, WallhavenSource.HttpStatus imageStatus) {
            if (g != seedGeneration.get()) {
                // a stale generation (session replaced OR seed renewed): drop it — an old-window decode must NEVER
                // consume the new window, and a late old-generation READY is ignored (plan B6, Codex round-2 P2).
                if (bmp != null) recycleBitmap(bmp);
                return;
            }
            decodeInFlight = false;
            if (!running) {
                if (bmp != null) recycleBitmap(bmp);
                return;
            }
            if (bmp != null) {
                onReady(bmp);
                return;
            }
            // any image fetch/decode failure (incl. a CDN 429, carrying imageStatus) is just an error: discard + clean
            // restart from a fresh seed (plan B6 simple model — NO per-descriptor salvage, NO consume-and-advance).
            onError(delayFor(imageStatus));
        }

        private void onReady(B bmp) {
            errorStreak = 0;          // a decoded bitmap — the ONLY thing that resets the error run / recovers the phase
            main.cancel(cooldownTick); // we are rendering again → cancel any pending clean restart
            cooldownDeadline = 0;      // healthy — clear the cooldown
            phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.READY); // → RENDERING (incl. out of EXHAUSTED)
            pager.consume();          // this descriptor is now shown/queued — advance the stream past it
            if (trans == Trans.BLACK) {
                pendingBitmap = bmp;
                pendingCaption = captionFor();
                maybeFadeIn();
            } else if (curBitmap == null) {
                showAsCurrent(bmp);   // the first image, or a recovery after the black-hold fallback / give-up
            } else {
                pendingBitmap = bmp;  // a prefetched next image while one is showing → hold until the display timer
                pendingCaption = captionFor();
            }
        }

        private void showAsCurrent(B bmp) {
            curBitmap = bmp;
            showArt(); // onPhase(RENDERING) — owner fades the whole art layer in
            stage.fadeInFromBlack(curLayer, bmp, captionFor(), FADE_IN_MS, null); // inner 0→1 → effective starts ~0
            // Replace the old single-image hold-forever: hold only when the stream is genuinely terminal (nothing more
            // buffered AND nothing more to fetch). For an endless seed-paged stream a fresh seed is always reachable,
            // so this normally starts the dwell timer and keeps paging.
            if (pager.current() == null && !pager.needsFetch()) return;
            startDisplayTimer();
            scheduleDecode();           // prefetch the next image during the hold
            scheduleRefillIfNeeded();   // and keep the page window filled
        }

        private void onDisplayElapsed() {
            if (!running || trans != Trans.NONE || curBitmap == null) return;
            trans = Trans.FADING_OUT;
            final int e = epoch.get();
            stage.fadeOutToBlack(curLayer, FADE_OUT_MS, () -> onFadeOutDone(e));
        }

        private void onFadeOutDone(int e) {
            if (e != epoch.get() || trans != Trans.FADING_OUT) return;
            stage.clear(curLayer);
            recycleBitmap(curBitmap);
            curBitmap = null;
            trans = Trans.BLACK;
            main.cancel(blackHoldTick);
            main.postDelayed(blackHoldTick, BLACK_HOLD_MAX_MS);
            maybeFadeIn();
        }

        private void maybeFadeIn() {
            if (trans != Trans.BLACK || pendingBitmap == null) return;
            main.cancel(blackHoldTick);
            int newLayer = 1 - curLayer;
            B bmp = pendingBitmap;
            String cap = pendingCaption;
            pendingBitmap = null;
            pendingCaption = null;
            curLayer = newLayer;
            curBitmap = bmp;
            trans = Trans.FADING_IN;
            final int e = epoch.get();
            stage.fadeInFromBlack(newLayer, bmp, cap, FADE_IN_MS, () -> onFadeInDone(e));
        }

        private void onFadeInDone(int e) {
            if (e != epoch.get() || trans != Trans.FADING_IN) return;
            trans = Trans.NONE;
            startDisplayTimer();
            scheduleDecode();
            scheduleRefillIfNeeded();
        }

        private void onBlackHoldTimeout() {
            if (!running || trans != Trans.BLACK) return;
            trans = Trans.NONE;
            hideArt(ArtPlayback.Phase.LOADING); // → starfield; a later ready image shows via showAsCurrent (recovery)
            scheduleDecode();
            scheduleRefillIfNeeded();
        }

        private void showArt() {
            if (artHidden) {
                artHidden = false;
                listener.onPhase(ArtPlayback.Phase.RENDERING);
            }
        }

        private void hideArt(ArtPlayback.Phase reason) {
            if (!artHidden) {
                artHidden = true;
                listener.onPhase(reason);
            }
        }

        private void startDisplayTimer() {
            main.cancel(displayTick);
            main.postDelayed(displayTick, ArtPlayback.DISPLAY_MS);
        }

        /** The per-image caption. Wallhaven descriptors carry no title/year, so it is empty for now; the courtesy
         * "wallhaven.cc" credit is a separate overlay (plan B9, a later bead). */
        private String captionFor() {
            return "";
        }
    }

    // ---- pure helpers / device decode ---------------------------------------------------------

    /** Whether the slideshow should run: attached to a window AND aggregate-visible (view hierarchy + window). */
    static boolean shouldRun(boolean attached, boolean aggregateVisible) {
        return attached && aggregateVisible;
    }

    /**
     * Decodes the in-memory image bytes at the panel size (plan B2/B4): a cheap bounds pass first (reject corrupt/
     * absurd dims AND verify the decoded format is JPEG/PNG via {@code outMimeType} — the authoritative sanitization
     * replacement), then a budget-sampled pixel decode. BOTH passes use the explicit {@code (buffer, 0, bytesRead)}
     * length, never {@code buffer.length}. null = unavailable. Device-only; the sizing math is {@link DecodeBudget}
     * (unit-tested). The caller catches {@link OutOfMemoryError} around this (plan B4 phase 2/3).
     */
    private static Bitmap decodeBitmap(WallhavenSource.Handle handle, int targetW, int targetH) {
        byte[] buf = handle.buffer;
        int len = handle.bytesRead;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(buf, 0, len, bounds);
        if (!DecodeBudget.acceptable(bounds.outWidth, bounds.outHeight)) return null;
        String mime = bounds.outMimeType;
        if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)) return null; // authoritative format gate
        // Account for the ACTUAL decoded stride: Android may promote a 16-bit PNG to RGBA_F16 (8 B/px) DESPITE an
        // ARGB_8888 preference, so the budget must use the config the bounds pass reports, not a hard-coded 4 B/px.
        int bytesPerPixel = bytesPerPixel(bounds.outConfig);
        int sample = DecodeBudget.sampleSizeForBudget(bounds.outWidth, bounds.outHeight,
                DecodeBudget.budgetBytes(targetW, targetH), bytesPerPixel);
        if (sample <= 0) return null; // cannot fit the decoded-byte budget even at the largest sane subsample
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(buf, 0, len, opts); // null on a corrupt body → treated as ITEM_UNAVAILABLE
    }

    /** The decoded bytes-per-pixel for a bounds-reported {@link Bitmap.Config}: 8 for RGBA_F16, otherwise 4 (the
     * ARGB_8888 stride we request; a conservative 4 for any null/other config so the budget never under-counts). */
    private static int bytesPerPixel(Bitmap.Config config) {
        return config == Bitmap.Config.RGBA_F16 ? 8 : 4;
    }

    // ---- production wiring (Android) ----------------------------------------------------------

    /** The production pipeline: each session gets its OWN {@link WallhavenSource} (fetch scope) so cancelling /
     * retiring one session never touches another and its stuck fetch exits fast. */
    private static final class WallhavenPipeline implements Pipeline<Bitmap> {
        WallhavenPipeline() {}

        @Override public Session<Bitmap> open() {
            // ACTIVE fetches keep the default 30s deadline; the short bound applies only on retire (cancel()).
            return new WallhavenSession(new WallhavenSource(HttpText.USER_AGENT));
        }
    }

    /** One fetch session over a dedicated {@link WallhavenSource}: {@code page} is the keyless search; {@code fetch}
     * yields the bounded in-memory image bytes; {@code decode} allocates the bitmap; {@code cancel} is a bounded shutdown. */
    private static final class WallhavenSession implements Session<Bitmap> {
        private final WallhavenSource source;

        WallhavenSession(WallhavenSource source) {
            this.source = source;
        }

        @Override public FetchResult<WallhavenSearch.Page> page(String seed, int page) {
            // Preset → Wallhaven q= tag mapping is a later bead (plan B2 / step 5); for now only the fixed SFW defaults.
            return source.search(null, seed, page);
        }

        @Override public FetchResult<Object> fetch(WallhavenImage image) {
            FetchResult<WallhavenSource.Handle> r = source.image(image);
            if (r.isOk()) return FetchResult.ok((Object) r.value());
            return r.propagate(); // preserve the structured error (e.g. a CDN 429 with Retry-After)
        }

        @Override public Bitmap decode(Object fetched, int targetW, int targetH) {
            return decodeBitmap((WallhavenSource.Handle) fetched, targetW, targetH);
        }

        @Override public void cancel() {
            source.shutdownWithin(RETIRE_ABORT_GRACE_MS);
        }
    }

    /** The shared background pool (ONE per {@code Screensaver}); idle threads die so a discarded Screensaver needs no
     * explicit shutdown. */
    static ExecutorService newSharedPool() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, POOL_KEEPALIVE_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "art-slideshow");
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    static Semaphore newSharedBudget() {
        return new Semaphore(MAX_LIVE_BITMAPS);
    }

    private final ImageView[] layers = new ImageView[2];
    private final TextView caption;
    private final Slideshow<Bitmap> slideshow;
    private boolean sized;
    private boolean aggregateVisible;

    /** @param storedPresetKey the persisted category preset; NOT yet translated to a Wallhaven {@code q=} tag (that
     * mapping is a later bead), so it is currently informational only. @param worker shared pool and @param bitmapBudget
     * shared budget are owned by the {@code Screensaver} and shared across ALL views/activations. */
    ArtScreensaverView(Context context, String storedPresetKey, Executor worker, Semaphore bitmapBudget,
            PhaseListener listener) {
        super(context);
        setBackgroundColor(Color.BLACK); // the letterbox behind every fitted image
        LayoutParams full = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        for (int i = 0; i < layers.length; i++) {
            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER); // aspect-preserving; Ken-Burns is a later bead
            iv.setAlpha(0f);
            layers[i] = iv;
            addView(iv, full);
        }
        caption = new TextView(context);
        caption.setTextColor(Color.WHITE);
        caption.setPadding(48, 24, 48, 24);
        LayoutParams captionLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        captionLp.gravity = Gravity.BOTTOM | Gravity.START;
        addView(caption, captionLp);

        Pipeline<Bitmap> pipeline = new WallhavenPipeline();
        Main main = new Main() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            @Override public boolean post(Runnable r) { return handler.post(r); }
            @Override public void postDelayed(Runnable r, long delayMs) { handler.postDelayed(r, delayMs); }
            @Override public void cancel(Runnable r) { handler.removeCallbacks(r); }
        };
        Clock clock = SystemClock::uptimeMillis; // the SAME monotonic timebase the Handler schedules delays against
        slideshow = new Slideshow<>(pipeline, new ViewStage(), worker, bitmapBudget, main, clock, listener);
    }

    /** The {@link Stage} over the two {@link ImageView}s + the shared caption. Transitions go through black, so only
     * ONE layer is ever visible. Main thread only. */
    private final class ViewStage implements Stage<Bitmap> {
        @Override public void fadeOutToBlack(int layer, long ms, Runnable onEnd) {
            caption.animate().alpha(0f).setDuration(ms).start();
            layers[layer].animate().alpha(0f).setDuration(ms).withEndAction(onEnd).start();
        }

        @Override public void fadeInFromBlack(int layer, Bitmap bitmap, String text, long ms, Runnable onEnd) {
            ImageView in = layers[layer];
            in.setImageBitmap(bitmap);
            caption.setText(text);
            caption.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
            in.setAlpha(0f);
            caption.setAlpha(0f);
            layers[1 - layer].setAlpha(0f);
            caption.animate().alpha(1f).setDuration(ms).start();
            in.animate().alpha(1f).setDuration(ms).withEndAction(onEnd).start();
        }

        @Override public void clear(int layer) {
            layers[layer].setImageDrawable(null);
        }

        @Override public void recycle(Bitmap bitmap) {
            bitmap.recycle();
        }

        @Override public void cancelAnimations() {
            caption.animate().cancel();
            for (ImageView iv : layers) iv.animate().cancel();
        }

        @Override public int[] boundsPx() {
            return new int[]{getWidth(), getHeight()};
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!sized && w > 0 && h > 0) {
            sized = true;
            slideshow.onBoundsReady();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        teardown();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        aggregateVisible = isVisible;
        updateRunning();
    }

    private void updateRunning() {
        if (shouldRun(isAttachedToWindow(), aggregateVisible)) slideshow.start();
        else slideshow.stop();
    }

    /** Terminal teardown of THIS view. Idempotent and non-blocking. The SHARED pool and bitmap budget OUTLIVE this view. */
    void teardown() {
        slideshow.teardown();
    }
}
