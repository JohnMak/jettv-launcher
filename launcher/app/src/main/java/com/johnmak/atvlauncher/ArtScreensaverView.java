package com.johnmak.atvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ART paintings screensaver layer: a slow fade-through-black slideshow of the preset-filtered vetted paintings fetched
 * through {@link ArtSource} and decoded from the verified cache file, over a black letterbox, with a {@code title · year}
 * caption. The starfield shows beneath it until the first image is ready and whenever the pool is exhausted/empty/
 * unreachable (the owner crossfades this whole layer's alpha on {@link PhaseListener#onPhase}).
 *
 * <p>Threading / memory contract (M3): the ONLY place state is mutated is the MAIN thread — the background pool does
 * nothing but the blocking {@link Session#queue()} / {@link Session#fetch} / {@link Session#decode} and then posts its
 * result back to main, so {@link Slideshow} needs no locks. The main thread captures the immutable decode job
 * {@code (viewWidth, viewHeight, epoch)} at schedule time; the worker receives only those values and never touches a View.
 * Decode sizing waits for nonzero bounds (the Dream prepares before layout), re-triggering the first item on the first
 * nonzero layout. Release order on replace/teardown: cancel timers/animators → clear the {@link ImageView} drawable
 * reference → recycle the bitmap (and release its budget permit).
 *
 * <p><b>Unified bounded model (Model B)</b> — a stopped session NEVER delays its replacement, yet threads AND bitmaps
 * stay hard-bounded across active + retiring sessions:
 * <ul>
 *   <li><b>One shared fixed pool, size 2.</b> The active session decodes on one thread; a retiring session's still-running
 *       fetch holds at most the other, so the replacement always has a thread. Pool size 2 HARD-bounds background threads
 *       regardless of activate/deactivate rate (no per-session executors, no accumulation). A stale queued task is a no-op
 *       (its epoch pre-check returns at once); a running one is aborted by its session's {@link ArtSource#shutdown()} and
 *       bounded by the injected short deadline.</li>
 *   <li><b>Shared bitmap budget {@code Semaphore(2)}.</b> A permit is acquired on the worker AFTER the fetch and BEFORE
 *       the bitmap allocation (a stuck fetch holds NO bitmap permit), and released when the bitmap is recycled (assigned
 *       → crossfade-recycle, or stale → discard-recycle). A late SUCCESSFUL decode from a retiring session must take a
 *       permit first → it blocks until the active session frees one → live bitmaps never exceed 2, across sessions.</li>
 *   <li><b>Session-scoped, admission-atomic cancellation.</b> Each session owns its own {@link ArtSource};
 *       {@link Session#cancel()} is {@link ArtSource#shutdown()} (synchronous {@code closed} flag → atomic admission
 *       refusal; non-blocking abort off-thread). The screensaver injects a SHORT request deadline so a retiring session's
 *       stuck/{@code :393}-window fetch exits fast, hard-closing the pool-occupancy residual.</li>
 * </ul>
 *
 * <p>Reuse: decode subsampling is {@link PosterSampling#sampleSize}, phase/index/failure logic is {@link ArtPlayback}.
 * Visibility handling intentionally DIVERGES from {@link VideoScreensaverView} (which keys off {@link #isShown()}): this
 * layer keys off the aggregate (view AND window) visibility from {@link #onVisibilityAggregated}, so an invisible window
 * reliably stops playback.
 */
final class ArtScreensaverView extends FlatFrameLayout {
    /** A source edge larger than this is rejected BEFORE any bitmap allocation (corrupt/oversized source = unavailable). */
    static final int MAX_SOURCE_EDGE = 4000;
    /** At most this many decoded bitmaps live at once, across the active and any retiring session (GLOBAL — the owning
     * {@code Screensaver} shares ONE {@code Semaphore(this)} over all views/sessions/activations). */
    static final int MAX_LIVE_BITMAPS = 2;
    /** Shared background threads (GLOBAL — one pool owned by {@code Screensaver}): the active session needs one, a
     * retiring fetch (vacating within {@link #RETIRE_ABORT_GRACE_MS}) at most one more. */
    static final int POOL_SIZE = 2;
    /** Idle shared-pool threads die after this, so a discarded {@code Screensaver} (e.g. a finished Dream) leaves no
     * lingering threads without needing an explicit pool shutdown. */
    static final long POOL_KEEPALIVE_MS = 10_000;
    /** On RETIRE, a session's in-flight request is aborted within ~this long (not the 30s active deadline) via
     * {@link ArtSource#shutdownWithin(long)} — so a stopped session vacates its pool thread fast and the replacement
     * never waits. ACTIVE fetches keep the default {@link ArtSource#REQUEST_DEADLINE_MS}. */
    static final long RETIRE_ABORT_GRACE_MS = 1_500;
    /** Each painting change fades the current image OUT to black, then the next IN from black (never a direct A↔B
     * crossfade). The black window is the LOAD COVER: a prefetched next appears instantly; any brief load latency
     * happens entirely behind the intentional black. Named for easy tuning (owner said "0.3s" each way). */
    static final long FADE_OUT_MS = 300;
    static final long FADE_IN_MS = 300;
    /** If the next image is still not ready this long after reaching black, stop holding on black and reveal the
     * starfield (tasteful fallback) rather than an indefinite black hold; the image fades in once it finally loads. */
    static final long BLACK_HOLD_MAX_MS = 2_000;

    /** Reports the slideshow phase so the owner ({@code Screensaver}) can crossfade this whole layer in/out. */
    interface PhaseListener {
        void onPhase(ArtPlayback.Phase phase);
    }

    // ---- injected seams (production wiring below; fakes in the test) --------------------------

    /** Opens fetch sessions. One session per {@link Slideshow#start()}; each has its own isolated fetch scope. */
    interface Pipeline<B> {
        Session<B> open();
    }

    /** One fetch session. {@link #queue()}, {@link #fetch} and {@link #decode} block and run on a pool thread only.
     * {@code fetch} and {@code decode} are SPLIT so the coordinator can take the shared bitmap-budget permit between
     * them (after I/O, before allocation). */
    interface Session<B> {
        /** The shuffled, preset-filtered play queue (D11 resolver applied); empty = stay on starfield. */
        List<ArtManifest.Entry> queue();

        /** Fetch (cache-or-guarded-fetch) the verified source for {@code entry}; null = unavailable. Holds NO bitmap
         * budget — a stuck fetch never pins memory. Bounded by the session's injected request deadline. */
        Object fetch(ArtManifest.Entry entry);

        /** Allocate+decode the bitmap from a {@code fetched} handle at the captured display size; null = unavailable
         * (corrupt/oversized source or decode failure). The caller already holds one bitmap-budget permit. */
        B decode(Object fetched, int targetW, int targetH);

        /** Cancel THIS session: SYNCHRONOUSLY refuse any further request admission (atomic with registration) and abort
         * in-flight I/O. Non-blocking to the caller; idempotent; affects no other session. Production =
         * {@link ArtSource#shutdown()}. */
        void cancel();
    }

    /** The main-thread rendering surface: two layers + a shared caption over a black letterbox. Transitions go THROUGH
     * BLACK — the two layers are never both visible at once. */
    interface Stage<B> {
        /** Fade {@code layer} OUT to black (alpha→0, with its caption) over {@code ms}; {@code onEnd} runs when fully
         * black. */
        void fadeOutToBlack(int layer, long ms, Runnable onEnd);

        /** Put {@code bitmap}+{@code caption} on {@code layer} and fade it IN from black (alpha 0→1) over {@code ms},
         * hiding the other layer; {@code onEnd} runs when fully shown. */
        void fadeInFromBlack(int layer, B bitmap, String caption, long ms, Runnable onEnd);

        /** Drop {@code layer}'s drawable reference (no recycle); always called before {@link #recycle} of its bitmap. */
        void clear(int layer);

        /** Free a bitmap. Precondition: it is referenced by no layer (its layer was {@link #clear}ed first). */
        void recycle(B bitmap);

        /** Cancel any running fade animators (so a cancelled fade never runs its end-action). */
        void cancelAnimations();

        /** Target decode size in px {@code {w, h}}; {@code {0, 0}} before layout. */
        int[] boundsPx();
    }

    /** Posts work onto the main thread (production: a {@link Handler}; test: inline/queued). {@link #post} returns
     * whether the runnable was accepted (mirrors {@link Handler#post} — false when the looper is dead/rejected), so the
     * caller can clean up a bitmap handoff that will never be delivered. */
    interface Main {
        boolean post(Runnable r);

        void postDelayed(Runnable r, long delayMs);

        void cancel(Runnable r);
    }

    // ---- the pure coordinator (the hard part; unit-tested offline) ----------------------------

    /**
     * The slideshow state machine. Pure (no Android types), single-threaded (every method here runs on {@code main}).
     * The shared {@code worker} pool and {@code bitmapBudget} semaphore span sessions; each session owns its own
     * {@link Session} (source). Every session has an atomic {@code epoch} (worker pre-check + stale-result discard).
     */
    static final class Slideshow<B> {
        private final Pipeline<B> pipeline;
        private final Stage<B> stage;
        private final Executor worker;
        private final Semaphore bitmapBudget; // shared across active + retiring sessions; cap = MAX_LIVE_BITMAPS
        private final Main main;
        private final PhaseListener listener;
        private final Runnable displayTick = this::onDisplayElapsed;
        private final Runnable blackHoldTick = this::onBlackHoldTimeout;

        /** The fade-through-black transition state (owner's design). NONE = steady (an image shown, or starfield when
         * {@code artHidden}); FADING_OUT = current image fading to black; BLACK = fully black, load cover, waiting for
         * the next image; FADING_IN = next image fading in from black. */
        private enum Trans { NONE, FADING_OUT, BLACK, FADING_IN }

        /** Session generation. Bumped on every {@link #start()} and every {@link #quiesce()}; read cross-thread by the
         * worker to pre-check cancellation before it admits any I/O (the cheap fast path). */
        private final AtomicInteger epoch = new AtomicInteger();
        private Session<B> currentSession;
        private boolean running;
        private boolean tornDown;
        private boolean decodeInFlight;
        private Trans trans = Trans.NONE;
        /** Whether the whole art layer is hidden (starfield showing) — initial LOADING, the black-hold fallback, and
         * EXHAUSTED. Controls when a ready image is brought in via the owner's layer-fade vs the inner fade-in. */
        private boolean artHidden = true;

        private List<ArtManifest.Entry> queue;
        private int count;
        private int cursor;          // next queue index to decode
        private int failureStreak;
        private ArtPlayback.Phase phase = ArtPlayback.Phase.LOADING;

        private int curLayer;        // the layer the current image is on (the only visible layer — never two at once)
        private B curBitmap;         // the current image's bitmap (null while black / starfield); holds one budget permit
        private B pendingBitmap;     // a decoded next image waiting to fade in; holds one budget permit
        private String pendingCaption;

        Slideshow(Pipeline<B> pipeline, Stage<B> stage, Executor worker, Semaphore bitmapBudget, Main main,
                PhaseListener listener) {
            this.pipeline = pipeline;
            this.stage = stage;
            this.worker = worker;
            this.bitmapBudget = bitmapBudget;
            this.main = main;
            this.listener = listener;
        }

        /** Begins (or restarts) a session: opens a fresh {@link Session}, bumps the epoch, shows the starfield
         * (LOADING), and loads the queue on the shared pool. Idempotent while running; refused after teardown. */
        void start() {
            if (running || tornDown) return;
            running = true;
            currentSession = pipeline.open();
            final Session<B> session = currentSession;
            final int e = epoch.incrementAndGet();
            decodeInFlight = false;
            trans = Trans.NONE;
            pendingBitmap = null;
            pendingCaption = null;
            queue = null;
            count = 0;
            cursor = 0;
            failureStreak = 0;
            phase = ArtPlayback.Phase.LOADING;
            artHidden = true;
            listener.onPhase(ArtPlayback.Phase.LOADING); // starfield shows until the first image is ready
            worker.execute(() -> {
                if (e != epoch.get()) return; // the session was stopped/replaced before this ran — fetch nothing
                final List<ArtManifest.Entry> q = session.queue();
                main.post(() -> onQueue(e, q));
            });
        }

        /** Pause: cancel the current session (scoped, admission-atomic), cancel timers/animators, bump the epoch, and
         * release the live bitmaps (and their budget permits). The shared pool/budget stay; a later {@link #start()}
         * opens a NEW session. */
        void stop() {
            if (!running) return;
            quiesce();
        }

        /** Terminal teardown: quiesce and refuse any further start. Idempotent. (The shared pool is disposed by the
         * owning view, not here.) */
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
            epoch.incrementAndGet(); // invalidate any in-flight worker result AND pre-admission fast-path check
            main.cancel(displayTick);
            main.cancel(blackHoldTick);
            stage.cancelAnimations();
            // Cancel ONLY the ending session, INLINE and synchronously, so a worker past the epoch pre-check but not yet
            // admitted is refused at registration; the blocking abort runs off-thread (ArtSource.shutdown) — no UI block.
            if (ending != null) ending.cancel();
            trans = Trans.NONE;
            releaseBitmaps();
        }

        /** Release order (M3): clear the layer's drawable reference first, then recycle + free the budget permit. A
         * pending bitmap was never on a layer, so it is recycle-only. At most these two bitmaps are ever live (the
         * shown one + one prefetched) — the two layers are never both visible (transitions go through black). */
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

        /** Recycle a decoded bitmap AND return its budget permit — paired one-to-one with the acquire in the worker. */
        private void recycleBitmap(B bitmap) {
            stage.recycle(bitmap);
            bitmapBudget.release();
        }

        private void onQueue(int e, List<ArtManifest.Entry> q) {
            if (e != epoch.get() || !running) return; // stale (a newer session started) — drop it
            queue = q;
            count = q.size();
            phase = ArtPlayback.initialPhase(count);
            if (count == 0) {
                listener.onPhase(phase); // EXHAUSTED: empty/unavailable pool → stay on starfield
                return;
            }
            scheduleDecode();
        }

        /** Submits a fetch+decode of {@code queue.get(cursor)} unless a gate forbids it: not running / torn down / a
         * decode is already in flight / a decoded image is already waiting (the third-decode gate — budget cap 2) /
         * the pool is exhausted or empty / no session / bounds not known yet (parked until onBoundsReady). */
        private void scheduleDecode() {
            if (!running || tornDown || decodeInFlight) return;
            if (pendingBitmap != null) return; // the next image is already decoded and waiting to fade in
            if (queue == null || queue.isEmpty() || phase == ArtPlayback.Phase.EXHAUSTED) return;
            if (currentSession == null) return;
            int[] b = stage.boundsPx();
            if (b[0] <= 0 || b[1] <= 0) return; // no bounds yet (Dream prepares before layout) — resumed by onBoundsReady
            decodeInFlight = true;
            final Session<B> session = currentSession;
            final int e = epoch.get();
            final ArtManifest.Entry entry = queue.get(cursor);
            final int w = b[0];
            final int h = b[1];
            worker.execute(() -> runFetchDecode(session, e, entry, w, h));
        }

        /** The pool task: fetch (no permit) → acquire the shared bitmap permit → allocate → post. A permit is held ONLY
         * while a bitmap exists, and is handed to {@link #onDecode} (released there on a stale/failed path, or carried by
         * the live bitmap until it is recycled). Pool thread only. */
        private void runFetchDecode(Session<B> session, int e, ArtManifest.Entry entry, int w, int h) {
            B decoded = null;
            boolean permit = false;
            try {
                if (e != epoch.get()) return;                 // stopped/replaced before this ran — admit no fetch
                Object fetched = session.fetch(entry);        // cancelled session → null; bounded by the retire grace
                if (fetched == null || e != epoch.get()) return;
                bitmapBudget.acquire();                        // AFTER fetch, BEFORE allocation — caps live bitmaps globally
                permit = true;
                if (e != epoch.get()) return;                 // stale while waiting for a permit — do not allocate
                decoded = session.decode(fetched, w, h);       // may return null (unavailable) or throw
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException decodeFailed) {
                // treated as unavailable; the finally releases the permit and still posts a result
            } finally {
                final B result = decoded;
                final boolean hadPermit = permit;
                // Ownership handoff (leak-safe). The ONLY successful handoff is a post that is ACCEPTED: only then does
                // onDecode own the bitmap + its permit (released on recycle, incl. the stale-discard path). Attempt the
                // single post inside the finally; on ANY failure to deliver (rejected/false or a thrown post) clean up
                // HERE — recycle the bitmap and release the permit — so a dead looper can never leak either. A non-bitmap
                // result carries no bitmap, so its reservation permit is freed here once the post is accepted.
                boolean accepted;
                try {
                    accepted = main.post(() -> onDecode(e, entry, result));
                } catch (RuntimeException postRejected) {
                    accepted = false;
                }
                if (!accepted) {
                    if (result != null) stage.recycle(result); // never assigned to a layer → safe to recycle off-main
                    if (hadPermit) bitmapBudget.release();
                } else if (result == null && hadPermit) {
                    bitmapBudget.release(); // accepted, but no bitmap — free the reservation (onDecode(null) won't)
                }
            }
        }

        private void onDecode(int e, ArtManifest.Entry entry, B bmp) {
            if (e != epoch.get()) { // a hide/detach/replace bumped the epoch — discard, never assign (and free its permit)
                if (bmp != null) recycleBitmap(bmp);
                return;
            }
            decodeInFlight = false;
            if (!running) {
                if (bmp != null) recycleBitmap(bmp);
                return;
            }
            if (bmp == null) {
                onItemUnavailable();
                return;
            }
            onReady(entry, bmp);
        }

        private void onItemUnavailable() {
            failureStreak = ArtPlayback.nextFailureStreak(failureStreak, ArtPlayback.Event.ITEM_UNAVAILABLE);
            ArtPlayback.Phase prev = phase;
            phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.ITEM_UNAVAILABLE, failureStreak, count);
            if (phase == ArtPlayback.Phase.EXHAUSTED) {
                main.cancel(blackHoldTick);
                trans = Trans.NONE;                 // abandon any pending transition
                if (prev != ArtPlayback.Phase.EXHAUSTED) { // emit once on the terminal transition → starfield
                    artHidden = true;
                    listener.onPhase(ArtPlayback.Phase.EXHAUSTED);
                }
                return; // stop attempting
            }
            cursor = ArtPlayback.nextIndex(cursor, count);
            scheduleDecode(); // advance past the bad item and keep trying to fill the next slot
        }

        private void onReady(ArtManifest.Entry entry, B bmp) {
            failureStreak = ArtPlayback.nextFailureStreak(failureStreak, ArtPlayback.Event.READY);
            phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.READY, failureStreak, count);
            if (trans == Trans.BLACK) {
                // the awaited image arrived during the black load-cover → fade it in now
                pendingBitmap = bmp;
                pendingCaption = captionOf(entry);
                maybeFadeIn();
            } else if (curBitmap == null) {
                // nothing is shown (the first image, or a recovery after the black-hold fallback) → bring the art
                // layer in WITH this image via the owner's whole-layer fade (no inner fade needed)
                showAsCurrent(entry, bmp);
            } else {
                // a prefetched next image while one is showing → hold it until the display timer fires
                pendingBitmap = bmp;
                pendingCaption = captionOf(entry);
            }
        }

        /** Brings {@code bmp} in as the new current image (the first image, or a recovery after the black-hold
         * fallback). The art layer is raised (starfield → art, the OUTER fade) AND the image is faded IN FROM BLACK
         * (the INNER fade 0→1). Because the effective on-screen alpha is outer × inner and the inner starts at 0, a
         * recovery image that arrives while the outer fade is still near full can NEVER cut to a bright painting — the
         * effective alpha always ramps from ~0 (no flash under any arrival timing). */
        private void showAsCurrent(ArtManifest.Entry entry, B bmp) {
            curBitmap = bmp;
            showArt(); // onPhase(RENDERING) — owner fades the whole art layer in (from 0, or from mid during recovery)
            stage.fadeInFromBlack(curLayer, bmp, captionOf(entry), FADE_IN_MS, null); // inner 0→1 → effective starts at ~0
            if (count == 1) return; // a single-image queue holds forever (no transitions)
            cursor = ArtPlayback.nextIndex(cursor, count);
            startDisplayTimer();
            scheduleDecode(); // prefetch the next image during the hold
        }

        private void onDisplayElapsed() {
            if (!running || trans != Trans.NONE || curBitmap == null) return;
            // begin the transition: fade the current image OUT to black
            trans = Trans.FADING_OUT;
            final int e = epoch.get();
            stage.fadeOutToBlack(curLayer, FADE_OUT_MS, () -> onFadeOutDone(e));
        }

        private void onFadeOutDone(int e) {
            if (e != epoch.get() || trans != Trans.FADING_OUT) return;
            stage.clear(curLayer);     // fully black now — drop the outgoing drawable reference,
            recycleBitmap(curBitmap);  // then recycle it + free its permit (release order)
            curBitmap = null;
            trans = Trans.BLACK;       // the load cover: hold on black until the next image is ready
            main.cancel(blackHoldTick);
            main.postDelayed(blackHoldTick, BLACK_HOLD_MAX_MS);
            maybeFadeIn();             // if the next was already prefetched, fade it in immediately
        }

        /** Fades the prefetched next image in from black once it is ready (while in the BLACK load-cover window). */
        private void maybeFadeIn() {
            if (trans != Trans.BLACK || pendingBitmap == null) return;
            main.cancel(blackHoldTick);
            int newLayer = 1 - curLayer;
            B bmp = pendingBitmap;
            String cap = pendingCaption;
            pendingBitmap = null;
            pendingCaption = null;
            curLayer = newLayer;
            curBitmap = bmp;           // the only live shown bitmap (the old one was recycled at fade-out)
            trans = Trans.FADING_IN;
            final int e = epoch.get();
            stage.fadeInFromBlack(newLayer, bmp, cap, FADE_IN_MS, () -> onFadeInDone(e));
        }

        private void onFadeInDone(int e) {
            if (e != epoch.get() || trans != Trans.FADING_IN) return;
            trans = Trans.NONE;
            cursor = ArtPlayback.nextIndex(cursor, count);
            startDisplayTimer();
            scheduleDecode(); // prefetch the next image during the hold
        }

        /** The next image did not arrive within {@link #BLACK_HOLD_MAX_MS} of reaching black (a rare long load): stop
         * holding on pure black and reveal the starfield. The image fades back in (as a recovery) once it finally
         * loads — never an endless black hold, never a frozen frame. */
        private void onBlackHoldTimeout() {
            if (!running || trans != Trans.BLACK) return;
            trans = Trans.NONE;
            hideArt(ArtPlayback.Phase.LOADING); // → starfield; a later ready image shows via showAsCurrent (recovery)
            scheduleDecode();                   // keep trying to load something to show
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
                listener.onPhase(reason); // LOADING or EXHAUSTED — both map to STARFIELD (art layer alpha 0)
            }
        }

        private void startDisplayTimer() {
            main.cancel(displayTick);
            main.postDelayed(displayTick, ArtPlayback.DISPLAY_MS);
        }
    }

    // ---- pure helpers (unit-tested; the BitmapFactory wrapper is device-only) -----------------

    /** True when a source image's bounds-decode dimensions are usable: positive and within {@link #MAX_SOURCE_EDGE}
     * on each edge. Checked BEFORE any bitmap allocation (D10) — a corrupt (≤0) or oversized source is unavailable. */
    static boolean acceptableSource(int srcW, int srcH) {
        return srcW > 0 && srcH > 0 && srcW <= MAX_SOURCE_EDGE && srcH <= MAX_SOURCE_EDGE;
    }

    /** The {@code title · year} caption (D4); artist omitted. Degrades gracefully when a part is empty. */
    static String captionOf(ArtManifest.Entry entry) {
        String title = entry.title == null ? "" : entry.title;
        String year = entry.year == null ? "" : entry.year;
        if (year.isEmpty()) return title;
        if (title.isEmpty()) return year;
        return title + " · " + year;
    }

    /** Whether the slideshow should run: attached to a window AND aggregate-visible (view hierarchy + window) — the
     * signal {@link #onVisibilityAggregated} delivers, which (unlike {@link #isShown()}) accounts for an invisible window. */
    static boolean shouldRun(boolean attached, boolean aggregateVisible) {
        return attached && aggregateVisible;
    }

    /** Decodes a verified cache file at display size from the file (no whole-image {@code byte[]}): bounds-decode first
     * and reject before any allocation (D10), then decode at a power-of-two sample size ({@link PosterSampling}). null =
     * unavailable. */
    private static Bitmap decodeBitmap(File file, int targetW, int targetH) {
        String path = file.getAbsolutePath();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (!acceptableSource(bounds.outWidth, bounds.outHeight)) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = PosterSampling.sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, opts); // null on a corrupt body → the UI treats it as ITEM_UNAVAILABLE
    }

    // ---- production wiring (Android) ----------------------------------------------------------

    /** The production pipeline: each session gets its OWN {@link ArtSource} (fetch+cache scope) built with the short ART
     * request deadline, so cancelling / retiring one session never touches another and its stuck fetch exits fast. */
    private static final class ArtPipeline implements Pipeline<Bitmap> {
        private final File filesDir;
        private final String storedPresetKey;

        ArtPipeline(File filesDir, String storedPresetKey) {
            this.filesDir = filesDir;
            this.storedPresetKey = storedPresetKey;
        }

        @Override public Session<Bitmap> open() {
            // ACTIVE fetches keep the default 30s deadline; the short bound applies only on retire (cancel()).
            ArtSource source = new ArtSource(new ArtCache(filesDir), HttpText.USER_AGENT);
            return new ArtSession(source, storedPresetKey, System.nanoTime());
        }
    }

    /** One fetch session over a dedicated {@link ArtSource}: the D11 resolver picks the pool; fetch yields the verified
     * cache file; decode streams the bitmap from it; cancel is {@link ArtSource#shutdown()}. */
    private static final class ArtSession implements Session<Bitmap> {
        private final ArtSource source;
        private final String storedPresetKey;
        private final long seed;

        ArtSession(ArtSource source, String storedPresetKey, long seed) {
            this.source = source;
            this.storedPresetKey = storedPresetKey;
            this.seed = seed;
        }

        @Override public List<ArtManifest.Entry> queue() {
            FetchResult<ArtManifest> r = source.manifest();
            if (!r.isOk()) return Collections.emptyList();
            ArtManifest manifest = r.value();
            ArtPreset preset = ArtPreset.effective(storedPresetKey, manifest.availablePresets());
            return manifest.shuffledQueue(preset, seed);
        }

        @Override public Object fetch(ArtManifest.Entry entry) {
            FetchResult<File> file = source.image(entry);
            return file.isOk() ? file.value() : null;
        }

        @Override public Bitmap decode(Object fetched, int targetW, int targetH) {
            return decodeBitmap((File) fetched, targetW, targetH);
        }

        @Override public void cancel() {
            // Retire fast: the in-flight fetch is aborted within the grace (not the 30s active deadline), so this
            // session's pool thread frees quickly and the replacement never waits. Non-blocking.
            source.shutdownWithin(RETIRE_ABORT_GRACE_MS);
        }
    }

    /** The shared background pool (finding 3): ONE per {@code Screensaver}, injected into every view so threads are
     * bounded GLOBALLY across activations. {@code allowCoreThreadTimeOut} lets idle threads die, so a discarded
     * {@code Screensaver} (e.g. a finished Dream) needs no explicit shutdown. */
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

    /** The shared bitmap budget (finding 3): ONE {@code Semaphore(MAX_LIVE_BITMAPS)} per {@code Screensaver}, injected
     * into every view so live bitmaps are bounded GLOBALLY across active + retiring sessions + activations. */
    static Semaphore newSharedBudget() {
        return new Semaphore(MAX_LIVE_BITMAPS);
    }

    private final ImageView[] layers = new ImageView[2];
    private final TextView caption;
    private final Slideshow<Bitmap> slideshow;
    private boolean sized;
    private boolean aggregateVisible;

    /** @param worker shared pool ({@link #newSharedPool}) and @param bitmapBudget shared budget ({@link #newSharedBudget})
     * both owned by the {@code Screensaver} and shared across ALL views/activations — NEVER created per view. */
    ArtScreensaverView(Context context, String storedPresetKey, Executor worker, Semaphore bitmapBudget,
            PhaseListener listener) {
        super(context);
        setBackgroundColor(Color.BLACK); // the letterbox behind every FIT_CENTER image
        LayoutParams full = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        for (int i = 0; i < layers.length; i++) {
            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER); // aspect-preserving, portrait-safe; no centre-crop
            iv.setAlpha(0f);
            layers[i] = iv;
            addView(iv, full);
        }
        caption = new TextView(context);
        caption.setTextColor(Color.WHITE);
        caption.setBackgroundColor(0x80000000); // a scrim so the caption stays readable over any painting
        caption.setPadding(48, 24, 48, 24);
        LayoutParams captionLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        captionLp.gravity = Gravity.BOTTOM | Gravity.START;
        addView(caption, captionLp);

        Pipeline<Bitmap> pipeline = new ArtPipeline(context.getFilesDir(), storedPresetKey);
        Main main = new Main() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            @Override public boolean post(Runnable r) { return handler.post(r); }
            @Override public void postDelayed(Runnable r, long delayMs) { handler.postDelayed(r, delayMs); }
            @Override public void cancel(Runnable r) { handler.removeCallbacks(r); }
        };
        slideshow = new Slideshow<>(pipeline, new ViewStage(), worker, bitmapBudget, main, listener);
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
            slideshow.onBoundsReady(); // the Dream prepares before layout — decode the first item now that bounds exist
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
        teardown(); // a detached art layer is never reused (the launcher re-prepares a fresh one each session)
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        aggregateVisible = isVisible; // honour window visibility, not just view visibility
        updateRunning();
    }

    private void updateRunning() {
        if (shouldRun(isAttachedToWindow(), aggregateVisible)) slideshow.start();
        else slideshow.stop();
    }

    /** Terminal teardown of THIS view; called by {@code Screensaver.prepare} before removing the layer, and on window
     * detach. Idempotent and non-blocking (safe on the main thread): the slideshow cancels the current session via a
     * bounded {@link ArtSource#shutdownWithin(long)} (retire grace ≤{@value #RETIRE_ABORT_GRACE_MS} ms), so its pool
     * thread frees fast. The SHARED pool and bitmap budget are OWNED BY {@code Screensaver} and OUTLIVE this view — they
     * are deliberately NOT shut down here (finding 3). */
    void teardown() {
        slideshow.teardown();
    }
}
