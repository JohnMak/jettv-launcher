package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Offline tests for {@link ArtScreensaverView}: pure helpers + the {@link ArtScreensaverView.Slideshow} coordinator
 * driven deterministically (no device, no BitmapFactory, no Views). Model B: ONE shared fixed pool + ONE shared bitmap
 * {@code Semaphore(2)} span sessions; each session owns its own {@link ArtScreensaverView.Session} (fake models
 * {@link ArtSource}: {@code fetch} refuses after cancellation; {@code decode} allocates). Deterministic tests use an
 * inline single-queue executor; the concurrency tests use a real fixed pool.
 */
public class ArtScreensaverViewTest {

    private static final Object TOKEN = new Object(); // a fetched "verified file" handle

    // ---- pure helpers -------------------------------------------------------------------------

    @Test public void acceptableSourceRejectsCorruptAndOversized() {
        assertTrue(ArtScreensaverView.acceptableSource(1920, 1080));
        assertTrue(ArtScreensaverView.acceptableSource(ArtScreensaverView.MAX_SOURCE_EDGE, ArtScreensaverView.MAX_SOURCE_EDGE));
        assertFalse("zero width = corrupt", ArtScreensaverView.acceptableSource(0, 100));
        assertFalse("zero height = corrupt", ArtScreensaverView.acceptableSource(100, 0));
        assertFalse("edge over the cap is rejected before allocation",
                ArtScreensaverView.acceptableSource(ArtScreensaverView.MAX_SOURCE_EDGE + 1, 10));
    }

    @Test public void captionIsTitleDotYearAndDegradesGracefully() {
        assertEquals("Morning · 1889", ArtScreensaverView.captionOf(entry(1, "Morning", "1889")));
        assertEquals("Morning", ArtScreensaverView.captionOf(entry(1, "Morning", "")));
        assertEquals("1889", ArtScreensaverView.captionOf(entry(1, "", "1889")));
        assertEquals("", ArtScreensaverView.captionOf(entry(1, "", "")));
    }

    @Test public void shouldRunHonoursAggregateWindowVisibility() {
        assertTrue(ArtScreensaverView.shouldRun(true, true));
        assertFalse("attached but the window is invisible (aggregate false) must not run",
                ArtScreensaverView.shouldRun(true, false));
        assertFalse("detached never runs", ArtScreensaverView.shouldRun(false, true));
        assertFalse(ArtScreensaverView.shouldRun(false, false));
    }

    // ---- the slideshow coordinator: happy path ------------------------------------------------

    @Test public void firstReadyImageRendersAndThenPrefetchesTheNext() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        assertEquals(Arrays.asList(ArtPlayback.Phase.LOADING), h.phases);
        h.runNext();                        // queue() loads [e0,e1,e2], schedules decode(0)
        h.runNext();                        // fetch+decode(0) -> first image shown
        assertEquals("art becomes visible on the first ready image",
                Arrays.asList(ArtPlayback.Phase.LOADING, ArtPlayback.Phase.RENDERING), h.phases);
        assertEquals("shown on layer 0", Integer.valueOf(h.pipeline.firstId), h.stage.layerBmp[0]);
        assertTrue("a display timer is armed for the hold", h.main.hasDelayed());
        assertEquals("the next image is being prefetched", 1, h.pending());
        assertEquals("one permit held for the shown bitmap", 1, h.budget.availablePermits());
    }

    @Test public void aTransitionFadesOutToBlackThenFadesTheNextInAndRecyclesTheOldInOrder() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue
        h.runNext();                        // decode(0) -> shown (id A) on layer 0
        int a = h.stage.layerBmp[0];
        h.runNext();                        // decode(1) -> prefetched (id B), waiting
        assertEquals("both permits held (shown + prefetched)", 0, h.budget.availablePermits());
        assertEquals("no third decode while an image is prefetched and waiting", 0, h.pending());

        h.main.fireDelayed();               // the hold elapses -> FADE OUT the current image begins
        assertTrue("the current image is fading out to black", h.stage.fadingOut());
        assertFalse("nothing is fading in yet (we go THROUGH black, never a direct crossfade)", h.stage.fadingIn());
        assertFalse("A is NOT recycled until it is fully black", h.stage.recycled.contains(a));

        h.stage.finishFadeOut();            // fully black -> outgoing cleared+recycled, next fades IN from black
        assertTrue("A recycled once fully black (in order: cleared then recycled)", h.stage.recycled.contains(a));
        assertNull("the old layer was cleared", h.stage.layerBmp[0]);
        assertEquals("the outgoing permit is freed at black", 1, h.budget.availablePermits());
        assertTrue("the next image is now fading in from black", h.stage.fadingIn());
        int b = h.stage.layerBmp[1];

        h.stage.finishFadeIn();             // fully shown -> advance, re-arm the hold, prefetch the following
        assertEquals("the new image is the shown one", Integer.valueOf(b), h.stage.layerBmp[1]);
        assertTrue("the display hold is re-armed", h.main.hasDelayed());
        assertEquals("only now is the following image prefetched", 1, h.pending());
        assertTrue("release order held throughout (no recycle-while-referenced, never two layers visible)", h.stage.ok());
    }

    // ---- M4 race: a late completion after replacement/teardown cannot assign or restart timers --

    @Test public void aDecodeCompletingAfterTeardownIsDiscardedNotAssigned() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue -> decode(0) scheduled
        h.slideshow.teardown();             // epoch bumped + session cancelled
        h.runNext();                        // decode(0) task: epoch pre-check skips it (admits no fetch)
        assertEquals("the stale task admitted no fetch", 0, h.pipeline.fetches);
        assertNull("no bitmap assigned to any layer after teardown", h.stage.layerBmp[0]);
        assertNull(h.stage.layerBmp[1]);
        assertFalse("no display timer was (re)armed", h.main.hasDelayed());
        assertEquals("art never became visible", Arrays.asList(ArtPlayback.Phase.LOADING), h.phases);
        assertEquals("the session was cancelled exactly once", 1, h.pipeline.cancels);
        assertEquals("all permits free again", 2, h.budget.availablePermits());
    }

    @Test public void aFetchThatRacesAStopHidePauseAdmitsNoFetchAndIsDiscarded() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue -> decode(0) scheduled
        h.pipeline.lastSession.onFetchEntered = h.slideshow::stop; // stop() the instant fetch() is entered
        h.runNext();                        // fetch(0): cancelled mid-flight -> admits no fetch; posts stale
        assertEquals("the session refused the fetch after cancellation", 0, h.pipeline.fetches);
        assertNull(h.stage.layerBmp[0]);
        assertFalse("no timer armed by the stale result", h.main.hasDelayed());
        assertEquals("no permit was taken (fetch held none)", 2, h.budget.availablePermits());
    }

    // ---- M3 release order: teardown mid-transition releases BOTH live bitmaps in order ---------

    @Test public void teardownMidFadeOutReleasesBothLiveBitmapsWithoutRecyclingWhileReferenced() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue
        h.runNext();                        // decode(0) -> shown (A) on its layer
        int a = h.stage.layerBmp[0];
        h.runNext();                        // decode(1) -> prefetched (B), in memory, not on a layer
        assertEquals("two bitmaps live (shown A + prefetched B)", 0, h.budget.availablePermits());
        h.main.fireDelayed();               // FADE OUT begins: A still on its layer (fading), B still pending
        assertTrue(h.stage.fadingOut());
        h.slideshow.teardown();             // tear down mid-fade-out: both A and B must be released
        assertTrue("the fading-out bitmap A was released", h.stage.recycled.contains(a));
        assertEquals("both live bitmaps released", 2, h.stage.recycled.size());
        assertNull(h.stage.layerBmp[0]);
        assertNull(h.stage.layerBmp[1]);
        assertTrue("every layer was cleared before its bitmap was recycled", h.stage.ok());
        assertEquals("both permits freed", 2, h.budget.availablePermits());
    }

    // ---- fade-through-black: load cover + long-load fallback (owner's design) ------------------

    @Test public void whenTheNextIsNotReadyAtBlackItHoldsOnBlackThenFadesInWhenItArrives() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue
        h.runNext();                        // decode(0) -> shown (A); prefetch decode(1) scheduled but NOT run yet
        assertEquals("the prefetch is scheduled, not run", 1, h.pending());
        h.main.fireDelayed();               // display hold elapses -> FADE OUT (the next is not ready)
        h.stage.finishFadeOut();            // fully black -> next still not ready -> HOLD on black (the load cover)
        assertFalse("not fading in — holding on black while the load completes behind it", h.stage.fadingIn());
        assertNull("screen is black (old recycled, next not shown)", h.stage.layerBmp[0]);
        assertNull(h.stage.layerBmp[1]);
        assertTrue("a bounded black-hold timeout is armed (never an indefinite hold)", h.main.hasDelayed());
        h.runNext();                        // the prefetch finally completes behind the black
        assertTrue("the next image fades IN from black once ready", h.stage.fadingIn());
        h.stage.finishFadeIn();
        assertEquals("the next image is now shown", ArtPlayback.Phase.RENDERING, last(h.phases));
    }

    @Test public void aLongLoadFallsBackToStarfieldThenRecoversWhenTheImageFinallyLoads() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue
        h.runNext();                        // decode(0) -> shown (A); prefetch decode(1) scheduled, NOT run (stuck)
        h.main.fireDelayed();               // display hold -> FADE OUT
        h.stage.finishFadeOut();            // -> BLACK, holding (prefetch still stuck), black-hold armed
        assertTrue(h.main.hasDelayed());
        h.main.fireDelayed();               // BLACK-HOLD TIMEOUT -> fall back to starfield (no endless black)
        assertEquals("the art layer is hidden → starfield (tasteful fallback, not endless black)",
                ArtPlayback.Phase.LOADING, last(h.phases));
        assertFalse("nothing is stuck fading", h.stage.fadingIn());
        h.runNext();                        // the stuck prefetch finally completes -> recovery (arrives mid-starfield-fade)
        assertEquals("recovered: an image is shown again", ArtPlayback.Phase.RENDERING, last(h.phases));
        assertNotNull("an image is shown on the layer after recovery", h.stage.layerBmp[0]);
        assertTrue("no glitch — release order held throughout", h.stage.ok());
        // NO FLASH: model the effective on-screen alpha = outer (art-layer, worst case ~1.0 right at timeout) × inner
        // (the ImageView). The recovery image must fade IN from black (inner starts at 0), so the effective alpha
        // ramps from ~0 and never cuts to a bright painting.
        assertEquals("the recovery image fades in from black (inner alpha starts at 0)", 0.0,
                h.stage.shownInnerStartAlpha, 1e-9);
        double effectiveStart = h.outer.alpha * h.stage.shownInnerStartAlpha;
        assertTrue("effective (outer×inner) alpha ramps from ~0 — no full-brightness cut (outer was "
                + h.outer.alpha + ")", effectiveStart < 0.1);
    }

    // ---- P2 (finding 1): a stopped session's cancel is scoped — never the replacement ----------

    @Test public void cancellingAStoppedSessionNeverAffectsTheReplacementSession() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();                // session A
        FakeSession a = h.pipeline.lastSession;
        h.runNext();                        // queue(A) -> schedules decode(A)
        h.slideshow.stop();                 // cancel A ONLY (synchronous, scoped)
        h.slideshow.start();                // session B
        FakeSession b = h.pipeline.lastSession;
        assertNotSame("a fresh session was opened for the restart", a, b);
        h.drain();                          // run the shared queue: A's stale decode is skipped; B fetches+shows
        assertTrue("session A was cancelled", a.myCancels >= 1);
        assertEquals("session B was NOT cancelled by A's teardown", 0, b.myCancels);
        assertFalse("session B is live", b.cancelled);
        assertEquals("session B rendered its own image", ArtPlayback.Phase.RENDERING, last(h.phases));
        assertTrue("session B admitted its fetch (A's cancel did not abort B)", h.pipeline.fetches >= 1);
    }

    // ---- P2 (finding 2): a worker past the epoch pre-check but resuming AFTER cancel admits no I/O

    @Test public void aWorkerResumingIntoAdmissionAfterCancelAdmitsNoIO() throws Exception {
        Rig r = new Rig(queue(3));
        try {
            r.slideshow.start();                // session A on the real pool
            FakeSession a = r.pipeline.lastSession;
            a.fetchEntered = new CountDownLatch(1);
            a.fetchRelease = new CountDownLatch(1); // hold the worker inside fetch(), past the epoch pre-check
            r.main.runNextPost();               // onQueue -> scheduleDecode submits the fetch/decode task
            assertTrue("worker passed the epoch pre-check and entered admission (fetch)",
                    a.fetchEntered.await(3, TimeUnit.SECONDS));
            r.slideshow.stop();                 // cancel A synchronously (atomic admission refusal)
            a.fetchRelease.countDown();         // worker resumes into the admission check inside fetch()
            r.main.runNextPost();               // onDecode (stale, no bitmap) -> discarded
            assertEquals("admitted NO fetch after the session was cancelled", 0, r.pipeline.fetches);
            assertTrue("session A was cancelled", a.myCancels >= 1);
        } finally {
            r.release();
        }
    }

    // ---- P2 (round 3): a stopped session's BLOCKED worker never delays the replacement ---------

    @Test public void aStoppedSessionsBlockedWorkerNeverDelaysTheReplacement() throws Exception {
        Rig r = new Rig(queue(1)); // single image: B is shown after one decode
        try {
            r.slideshow.start();                // session A on the real pool
            FakeSession a = r.pipeline.lastSession;
            a.fetchEntered = new CountDownLatch(1);
            a.fetchRelease = new CountDownLatch(1); // A's fetch blocks here UNINTERRUPTIBLY (a stuck read)
            r.main.runNextPost();               // onQueue(A) -> schedules fetch(A) on a pool thread
            assertTrue("A's fetch is running and blocked", a.fetchEntered.await(3, TimeUnit.SECONDS));
            r.slideshow.stop();                 // A still blocked on its thread
            r.slideshow.start();                // session B on the SHARED pool's OTHER thread
            FakeSession b = r.pipeline.lastSession;
            assertNotSame(a, b);
            r.main.runNextPost();               // onQueue(B) -> schedules fetch/decode(B)
            r.main.runNextPost();               // onDecode(B) -> B shown; it RAN while A is still blocked
            assertEquals("B rendered while A's worker is still blocked", ArtPlayback.Phase.RENDERING, last(r.phases));
            assertTrue("B admitted its own fetch on the other pool thread", r.pipeline.fetches >= 1);
            assertEquals("A is still blocked (its stuck task never gated B)", 1, a.fetchRelease.getCount());
        } finally {
            r.release();
        }
    }

    // ---- P2 (round 4, finding 1): threads stay bounded under repeated start/stop with stuck work

    @Test public void repeatedStartStopKeepsBackgroundThreadsBounded() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonFactory());
        Semaphore budget = new Semaphore(ArtScreensaverView.MAX_LIVE_BITMAPS);
        FakePipeline pipeline = new FakePipeline(queue(3));
        pipeline.queueGate = new CountDownLatch(1); // EVERY session's queue() sticks here (uninterruptible)
        FakeStage stage = new FakeStage();
        List<ArtPlayback.Phase> phases = new ArrayList<>();
        ArtScreensaverView.Slideshow<Integer> slideshow =
                new ArtScreensaverView.Slideshow<>(pipeline, stage, pool, budget, new QueuingMain(), phases::add);
        try {
            for (int i = 0; i < 12; i++) { // rapid activate/deactivate — each queue() task sticks on a pool thread
                slideshow.start();
                slideshow.stop();
            }
            assertEquals("every activation opened its own session", 12, pipeline.sessions.size());
            assertTrue("background threads stay bounded by the shared pool (never per-session accumulation), saw "
                    + pool.getLargestPoolSize(), pool.getLargestPoolSize() <= 2);
        } finally {
            pipeline.queueGate.countDown();
            pool.shutdownNow();
        }
    }

    // ---- P2 (round 4, finding 2): the two-bitmap budget holds across active + retiring sessions -

    @Test public void theBitmapBudgetHoldsAcrossActiveAndRetiringSessions() throws Exception {
        Rig r = new Rig(queue(3));
        try {
            // Session A: show img0 (permit #1), then START the prefetch of img1 and HOLD it mid-decode so it keeps
            // permit #2 — a retiring session with an in-flight SUCCESSFUL decode.
            r.slideshow.start();
            FakeSession a = r.pipeline.lastSession;
            a.holdDecodeCall = 2;                       // hold A's 2nd decode (the prefetch)
            a.heldDecodeEntered = new CountDownLatch(1);
            a.heldDecodeRelease = new CountDownLatch(1);
            r.main.runNextPost();                       // onQueue(A) -> fetch/decode(0)
            r.main.runNextPost();                       // onDecode(img0) -> cur shown (permit #1); prefetch scheduled
            assertTrue("A's prefetch acquired permit #2 and is allocating",
                    a.heldDecodeEntered.await(3, TimeUnit.SECONDS));
            assertEquals("both permits held (cur + in-flight prefetch)", 0, r.budget.availablePermits());

            r.slideshow.stop();                         // retire A: releases cur's permit; the in-flight prefetch keeps its
            assertEquals("one permit freed by A's cur; the in-flight prefetch still holds one", 1, r.budget.availablePermits());

            r.slideshow.start();                        // session B (fresh)
            FakeSession b = r.pipeline.lastSession;
            b.holdFetchCall = 2;                        // HOLD B's prefetch fetch so it cannot grab a freed permit
            b.heldFetchEntered = new CountDownLatch(1); //   mid-assertion (finding 4 — the race was B's prefetch)
            b.heldFetchRelease = new CountDownLatch(1);
            r.main.runNextPost();                       // onQueue(B) -> fetch/decode(0)
            r.main.runNextPost();                       // onDecode(B img0) -> B cur (takes the one free permit); prefetch scheduled
            assertEquals("budget exhausted: B-cur + A-in-flight = 2 live, so no 3rd can allocate",
                    0, r.budget.availablePermits());
            assertEquals(ArtPlayback.Phase.RENDERING, last(r.phases));
            assertTrue("B's prefetch is parked in fetch, holding NO permit", b.heldFetchEntered.await(3, TimeUnit.SECONDS));

            a.heldDecodeRelease.countDown();            // A's late decode completes -> posts stale -> discarded + freed
            r.main.runNextPost();                       // onDecode(A img1, stale) -> recycled, permit released
            assertEquals("the freed permit is NOT grabbed by B's parked prefetch (finding 4 fixed)",
                    1, r.budget.availablePermits());
            assertTrue("live bitmaps never exceeded the budget", stage(r).ok());
        } finally {
            r.release(); // releases every session's held fetch/decode latches, then shuts the pool down
        }
    }

    // ---- P2 (finding 1): the bitmap permit is never leaked on an exception / null-decode path --

    @Test public void aDecodeThatThrowsReleasesItsPermitAndReportsUnavailable() {
        Harness h = new Harness(queue(2));
        h.slideshow.start();
        h.runNext();                        // queue -> decode(0) scheduled
        h.pipeline.lastSession.decodeThrowOnCall = 1; // the first decode throws AFTER the permit was acquired
        h.runNext();                        // fetch ok -> acquire -> decode THROWS -> finally releases permit, posts unavailable
        assertEquals("the permit was released despite the decode throwing", ArtScreensaverView.MAX_LIVE_BITMAPS,
                h.budget.availablePermits());
        assertEquals("treated as unavailable (still LOADING, advanced)", ArtPlayback.Phase.LOADING, last(h.phases));
        assertEquals("decodeInFlight was cleared → it retried the next entry", 1, h.pending());
    }

    @Test public void aDecodeThatReturnsNullAfterFetchReleasesItsPermit() {
        Harness h = new Harness(queue(2));
        h.slideshow.start();
        h.runNext();                        // queue -> decode(0) scheduled
        h.pipeline.lastSession.decodeNullOnCall = 1; // fetch succeeds, allocation rejected (null) — permit held then freed
        h.runNext();                        // fetch ok -> acquire -> decode null -> finally releases permit, posts unavailable
        assertEquals("the permit was released when decode returned null", ArtScreensaverView.MAX_LIVE_BITMAPS,
                h.budget.availablePermits());
        assertEquals(ArtPlayback.Phase.LOADING, last(h.phases));
        assertEquals("decodeInFlight was cleared → it retried the next entry", 1, h.pending());
    }

    @Test public void aRejectedBitmapHandoffPostReleasesThePermitAndRecyclesTheBitmap() {
        // The success handoff (Main.post of the decoded bitmap) is rejected (dead looper): the bitmap must be recycled
        // and its permit released HERE, never leaked, since onDecode will never run to take ownership.
        ManualExecutor worker = new ManualExecutor();
        Semaphore budget = new Semaphore(ArtScreensaverView.MAX_LIVE_BITMAPS);
        RejectingMain main = new RejectingMain(2); // post #1 = onQueue (runs); post #2 = the bitmap handoff (rejected)
        FakePipeline pipeline = new FakePipeline(queue(2));
        FakeStage stage = new FakeStage();
        List<ArtPlayback.Phase> phases = new ArrayList<>();
        ArtScreensaverView.Slideshow<Integer> s =
                new ArtScreensaverView.Slideshow<>(pipeline, stage, worker, budget, main, phases::add);
        s.start();
        assertTrue(worker.runNext());   // queue -> post#1 onQueue (accepted) -> schedule decode(0)
        assertTrue(worker.runNext());   // fetch -> acquire -> decode(id) -> handoff post#2 REJECTED
        assertEquals("the permit was released when the handoff post was rejected",
                ArtScreensaverView.MAX_LIVE_BITMAPS, budget.availablePermits());
        assertEquals("the undelivered bitmap was recycled (not leaked)", 1, stage.recycled.size());
        assertNull("nothing was assigned to a layer", stage.layerBmp[0]);
        assertNull(stage.layerBmp[1]);
    }

    // ---- lifecycle: exhaustion, empty pool, cancellation, single image, no-leak, bounds --------

    @Test public void aFullyFailingPoolExhaustsToStarfieldWithoutEverBlanking() {
        Harness h = new Harness(queue(2));
        h.pipeline.availability.addAll(Arrays.asList(false, false)); // both items unavailable (fetch fails)
        h.slideshow.start();
        h.runNext();                        // queue -> fetch(0)
        h.runNext();                        // fetch(0) fails -> advance (still LOADING)
        h.runNext();                        // fetch(1) fails -> full failing pass -> EXHAUSTED
        assertEquals(Arrays.asList(ArtPlayback.Phase.LOADING, ArtPlayback.Phase.EXHAUSTED), h.phases);
        assertNull("nothing was ever shown", h.stage.layerBmp[0]);
        assertEquals("no permit ever taken for a failed fetch", 2, h.budget.availablePermits());
        assertEquals("no further decode after exhaustion", 0, h.pending());
    }

    @Test public void failureAfterRenderingKeepsTheCurrentImageShown() {
        Harness h = new Harness(queue(3));
        h.pipeline.availability.addAll(Arrays.asList(true, false)); // first ok, the prefetch fetch fails
        h.slideshow.start();
        h.runNext();                        // queue
        h.runNext();                        // fetch+decode(0) ok -> shown (A), RENDERING
        int a = h.stage.layerBmp[0];
        h.runNext();                        // fetch(1) fails -> advance, current image held, no blank
        assertEquals(Integer.valueOf(a), h.stage.layerBmp[0]);
        assertEquals("still RENDERING (a single bad item never blanks)", ArtPlayback.Phase.RENDERING, last(h.phases));
        assertFalse("the shown bitmap was not recycled", h.stage.recycled.contains(a));
        assertEquals("it retries the following entry", 1, h.pending());
    }

    @Test public void anEmptyPoolStaysOnStarfieldAndDecodesNothing() {
        Harness h = new Harness(Collections.emptyList());
        h.slideshow.start();
        h.runNext();                        // queue() returns empty
        assertEquals(Arrays.asList(ArtPlayback.Phase.LOADING, ArtPlayback.Phase.EXHAUSTED), h.phases);
        assertEquals("no decode is ever scheduled for an empty pool", 0, h.pending());
    }

    @Test public void aSingleImageQueueHoldsWithoutCrossfadeOrFurtherDecode() {
        Harness h = new Harness(queue(1));
        h.slideshow.start();
        h.runNext();                        // queue (one entry)
        h.runNext();                        // decode(0) -> shown
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
        assertFalse("no display timer for a single image (it holds forever)", h.main.hasDelayed());
        assertEquals("no prefetch for a single-image queue", 0, h.pending());
        assertEquals("one permit held for the single image", 1, h.budget.availablePermits());
    }

    @Test public void stopReleasesTheShownBitmapCancelsTheSessionAndStaysRestartable() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue
        h.runNext();                        // decode(0) -> shown (A), prefetch scheduled
        int a = h.stage.layerBmp[0];
        h.slideshow.stop();                 // hide/detach: cancel session, release, cancel timer
        assertTrue("the shown bitmap was released", h.stage.recycled.contains(a));
        assertNull(h.stage.layerBmp[0]);
        assertFalse("the display timer was cancelled", h.main.hasDelayed());
        assertEquals("the session was cancelled", 1, h.pipeline.cancels);
        assertEquals("the permit was freed", 2, h.budget.availablePermits());
        h.slideshow.start();                // stop is non-terminal: a fresh session can start
        assertTrue(h.slideshow.isRunning());
        assertEquals("a new session was opened on restart", 2, h.pipeline.sessions.size());
    }

    @Test public void noBitmapLeaksAfterTeardown() {
        Harness h = new Harness(queue(3));
        h.slideshow.start();
        h.runNext();                        // queue
        h.runNext();                        // decode(0) -> shown (A)
        h.runNext();                        // decode(1) -> pending (B)
        h.slideshow.teardown();             // both A (shown) and B (pending) must be released
        assertEquals("every decoded bitmap handed out was released", h.pipeline.handedOut, h.stage.recycled);
        assertEquals("the session was cancelled once", 1, h.pipeline.cancels);
        assertEquals("all permits returned", 2, h.budget.availablePermits());
    }

    @Test public void decodeWaitsForNonzeroBoundsThenRunsOnFirstLayout() {
        Harness h = new Harness(queue(2));
        h.stage.w = 0;                      // not laid out yet (Dream)
        h.stage.h = 0;
        h.slideshow.start();
        h.runNext();                        // queue -> scheduleDecode parks (no bounds)
        assertEquals("no decode is scheduled before layout", 0, h.pending());
        h.stage.w = 1920;
        h.stage.h = 1080;
        h.slideshow.onBoundsReady();        // first nonzero layout re-triggers the first item
        assertEquals("the first decode runs once bounds exist", 1, h.pending());
        h.runNext();                        // decode(0) -> shown
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
    }

    // ---- harness (deterministic: inline main + single shared manual executor) -----------------

    private static ArtPlayback.Phase last(List<ArtPlayback.Phase> phases) {
        return phases.get(phases.size() - 1);
    }

    private static FakeStage stage(Rig r) {
        return r.stage;
    }

    /** Models the OUTER art-layer alpha the Screensaver drives from onPhase, and records the phase sequence.
     * RENDERING → the layer is/settles opaque (1.0). LOADING/EXHAUSTED → the fade toward the starfield has only JUST
     * begun, so the outer alpha is still at its current (high) value — we deliberately do NOT drop it, modelling the
     * WORST case for a recovery that arrives right at the black-hold timeout. */
    private static final class OuterAlpha implements ArtScreensaverView.PhaseListener {
        final List<ArtPlayback.Phase> phases = new ArrayList<>();
        double alpha = 0.0; // the art layer starts hidden (starfield)

        @Override public void onPhase(ArtPlayback.Phase phase) {
            phases.add(phase);
            if (phase == ArtPlayback.Phase.RENDERING) alpha = 1.0;
        }
    }

    private static final class Harness {
        final FakePipeline pipeline;
        final FakeStage stage = new FakeStage();
        final ManualExecutor worker = new ManualExecutor();
        final Semaphore budget = new Semaphore(ArtScreensaverView.MAX_LIVE_BITMAPS);
        final FakeMain main = new FakeMain();
        final OuterAlpha outer = new OuterAlpha();
        final List<ArtPlayback.Phase> phases = outer.phases; // alias — the outer model also records the phase sequence
        final ArtScreensaverView.Slideshow<Integer> slideshow;

        Harness(List<ArtManifest.Entry> queue) {
            pipeline = new FakePipeline(queue);
            slideshow = new ArtScreensaverView.Slideshow<>(pipeline, stage, worker, budget, main, outer);
        }

        boolean runNext() {
            return worker.runNext();
        }

        int pending() {
            return worker.pending();
        }

        void drain() {
            int guard = 0;
            while (worker.runNext() && guard++ < 100) { /* run the shared queue to completion */ }
        }
    }

    /** Real-pool rig for the concurrency tests: a fixed(2) pool + a test-drained main. */
    private static final class Rig {
        final FakePipeline pipeline;
        final FakeStage stage = new FakeStage();
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonFactory());
        final Semaphore budget = new Semaphore(ArtScreensaverView.MAX_LIVE_BITMAPS);
        final QueuingMain main = new QueuingMain();
        final List<ArtPlayback.Phase> phases = new ArrayList<>();
        final ArtScreensaverView.Slideshow<Integer> slideshow;

        Rig(List<ArtManifest.Entry> queue) {
            pipeline = new FakePipeline(queue);
            slideshow = new ArtScreensaverView.Slideshow<>(pipeline, stage, pool, budget, main, phases::add);
        }

        void release() {
            for (FakeSession s : pipeline.sessions) {
                if (s.fetchRelease != null) s.fetchRelease.countDown();
                if (s.heldFetchRelease != null) s.heldFetchRelease.countDown();
                if (s.heldDecodeRelease != null) s.heldDecodeRelease.countDown();
            }
            pool.shutdownNow();
        }
    }

    private static ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "test-art-worker");
            t.setDaemon(true);
            return t;
        };
    }

    /** A controllable session factory; aggregate counters are shared so the tests can read them off the pipeline. */
    private static final class FakePipeline implements ArtScreensaverView.Pipeline<Integer> {
        private final List<ArtManifest.Entry> queue;
        final Deque<Boolean> availability = new ArrayDeque<>(); // poll per fetch; empty = success
        final Set<Integer> handedOut = new HashSet<>();
        final List<FakeSession> sessions = new ArrayList<>();
        CountDownLatch queueGate;   // if set, every session's queue() sticks here (for the thread-bound test)
        int decodeInvocations;
        int fetches;                // fetch() calls that admitted (returned a token)
        int cancels;
        int nextId = 100;
        int firstId = -1;
        FakeSession lastSession;

        FakePipeline(List<ArtManifest.Entry> queue) {
            this.queue = queue;
        }

        @Override public ArtScreensaverView.Session<Integer> open() {
            FakeSession s = new FakeSession(this);
            sessions.add(s);
            lastSession = s;
            return s;
        }
    }

    /** Models {@link ArtSource}: {@code fetch} refuses (returns null) once cancelled — no I/O admitted; {@code decode}
     * allocates a fresh id (a successful, possibly late, decode). Blocking holds are UNINTERRUPTIBLE (like a socket
     * read), so a worker interrupt cannot free them — only an explicit release can. */
    private static final class FakeSession implements ArtScreensaverView.Session<Integer> {
        private final FakePipeline p;
        volatile boolean cancelled;
        int myCancels;
        volatile CountDownLatch fetchEntered;
        volatile CountDownLatch fetchRelease;
        Runnable onFetchEntered;
        int holdFetchCall;                      // 0 = none; else the 1-based fetch call to hold (post-entry)
        volatile CountDownLatch heldFetchEntered;
        volatile CountDownLatch heldFetchRelease;
        int holdDecodeCall;                     // 0 = none; else the 1-based decode call to hold
        volatile CountDownLatch heldDecodeEntered;
        volatile CountDownLatch heldDecodeRelease;
        int decodeThrowOnCall;                  // 0 = none; else the 1-based decode call that throws (permit-leak test)
        int decodeNullOnCall;                   // 0 = none; else the 1-based decode call that returns null
        private int fetchCalls;
        private int decodeCalls;

        FakeSession(FakePipeline p) {
            this.p = p;
        }

        @Override public List<ArtManifest.Entry> queue() {
            holdUninterruptibly(p.queueGate);
            return cancelled ? Collections.emptyList() : p.queue;
        }

        @Override public Object fetch(ArtManifest.Entry entry) {
            int call = ++fetchCalls;
            if (fetchEntered != null) fetchEntered.countDown();
            if (onFetchEntered != null) {
                Runnable r = onFetchEntered;
                onFetchEntered = null;
                r.run();
            }
            holdUninterruptibly(fetchRelease);
            if (call == holdFetchCall) {
                if (heldFetchEntered != null) heldFetchEntered.countDown();
                holdUninterruptibly(heldFetchRelease);
            }
            if (cancelled) return null;         // admission refused -> NO I/O, NO permit
            Boolean ok = p.availability.poll();
            if (ok != null && !ok) return null; // fetch failed -> ITEM_UNAVAILABLE
            p.fetches++;
            return TOKEN;
        }

        @Override public Integer decode(Object fetched, int targetW, int targetH) {
            int call = ++decodeCalls;
            p.decodeInvocations++;
            if (call == holdDecodeCall) {
                if (heldDecodeEntered != null) heldDecodeEntered.countDown();
                holdUninterruptibly(heldDecodeRelease);
            }
            if (call == decodeThrowOnCall) throw new RuntimeException("decode boom"); // the caller holds a permit here
            if (call == decodeNullOnCall) return null;                                // the caller holds a permit here
            int id = p.nextId++;
            if (p.firstId < 0) p.firstId = id;
            p.handedOut.add(id);
            return id;                          // a successful allocation (the caller holds a budget permit)
        }

        @Override public void cancel() {
            cancelled = true;
            myCancels++;
            p.cancels++;
        }

        private static void holdUninterruptibly(CountDownLatch l) {
            if (l == null) return;
            boolean interrupted = false;
            while (l.getCount() > 0) {
                try {
                    l.await();
                } catch (InterruptedException e) {
                    interrupted = true; // swallow and keep holding, like an uninterruptible socket read
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** A fake stage (fade-through-black) that tracks each layer's bitmap and FAILS LOUDLY if a bitmap is recycled while
     * still referenced, or if both layers ever hold a bitmap at once (transitions must go through black). */
    private static final class FakeStage implements ArtScreensaverView.Stage<Integer> {
        int w = 1920;
        int h = 1080;
        final Integer[] layerBmp = new Integer[2];
        final Set<Integer> recycled = new HashSet<>();
        /** The INNER alpha the current image began displaying at: every present is a fade-IN-from-black, so this is
         * always 0.0 — a regression that reintroduced an instant (alpha-1) show would make it 1.0 and flash. */
        double shownInnerStartAlpha = -1;
        private Runnable fadeOutEnd;
        private Runnable fadeInEnd;
        private boolean violated;

        @Override public void fadeOutToBlack(int layer, long ms, Runnable onEnd) {
            fadeOutEnd = onEnd; // the layer keeps its bitmap until clear() after the fade-out completes
        }

        @Override public void fadeInFromBlack(int layer, Integer bitmap, String caption, long ms, Runnable onEnd) {
            layerBmp[layer] = bitmap;
            shownInnerStartAlpha = 0.0; // the image is presented at alpha 0 and ramps up (no bright cut)
            fadeInEnd = onEnd;
        }

        @Override public void clear(int layer) {
            layerBmp[layer] = null;
        }

        @Override public void recycle(Integer bitmap) {
            if (bitmap.equals(layerBmp[0]) || bitmap.equals(layerBmp[1])) {
                violated = true; // recycled while a layer still references it — release-order invariant broke
            }
            recycled.add(bitmap);
        }

        @Override public void cancelAnimations() {
            fadeOutEnd = null;
            fadeInEnd = null;
        }

        @Override public int[] boundsPx() {
            return new int[]{w, h};
        }

        void finishFadeOut() {
            Runnable r = fadeOutEnd;
            fadeOutEnd = null;
            if (r != null) r.run();
        }

        void finishFadeIn() {
            Runnable r = fadeInEnd;
            fadeInEnd = null;
            if (r != null) r.run();
        }

        boolean fadingOut() {
            return fadeOutEnd != null;
        }

        boolean fadingIn() {
            return fadeInEnd != null;
        }

        boolean ok() {
            return !violated;
        }
    }

    /** A single shared manual executor (models the shared pool for deterministic tests): tasks run when pulled. */
    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable command) {
            tasks.add(command);
        }

        boolean runNext() {
            Runnable r = tasks.poll();
            if (r == null) return false;
            r.run();
            return true;
        }

        int pending() {
            return tasks.size();
        }
    }

    /** A main that runs posts inline (deterministic) and holds a single delayed task the test fires on demand. */
    private static final class FakeMain implements ArtScreensaverView.Main {
        private Runnable delayed;

        @Override public boolean post(Runnable r) {
            r.run();
            return true;
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delayed = r;
        }

        @Override public void cancel(Runnable r) {
            if (delayed == r) delayed = null;
        }

        boolean hasDelayed() {
            return delayed != null;
        }

        void fireDelayed() {
            Runnable r = delayed;
            delayed = null;
            if (r != null) r.run();
        }
    }

    /** A main whose posts are drained by the test thread (so coordinator state stays single-threaded while real pool
     * threads run the blocking fetch/decode). */
    private static final class QueuingMain implements ArtScreensaverView.Main {
        private final BlockingQueue<Runnable> posts = new LinkedBlockingQueue<>();
        private Runnable delayed;

        @Override public boolean post(Runnable r) {
            posts.add(r);
            return true;
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delayed = r;
        }

        @Override public void cancel(Runnable r) {
            if (delayed == r) delayed = null;
        }

        void runNextPost() throws InterruptedException {
            Runnable r = posts.poll(3, TimeUnit.SECONDS);
            assertNotNull("expected a posted main task", r);
            r.run();
        }
    }

    /** A main that runs posts inline but REJECTS the {@code rejectAt}-th post (returns false, drops the runnable) —
     * models {@link android.os.Handler#post} failing on a dead looper. */
    private static final class RejectingMain implements ArtScreensaverView.Main {
        private final int rejectAt;
        private int count;
        private Runnable delayed;

        RejectingMain(int rejectAt) {
            this.rejectAt = rejectAt;
        }

        @Override public boolean post(Runnable r) {
            if (++count == rejectAt) return false; // dropped, not run
            r.run();
            return true;
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delayed = r;
        }

        @Override public void cancel(Runnable r) {
            if (delayed == r) delayed = null;
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static List<ArtManifest.Entry> queue(int n) {
        List<ArtManifest.Entry> q = new ArrayList<>();
        for (int i = 0; i < n; i++) q.add(entry(i + 1, "Painting " + i, "19" + (10 + i)));
        return q;
    }

    private static ArtManifest.Entry entry(long id, String title, String year) {
        return new ArtManifest.Entry(id, title, year, Collections.emptyList(),
                id + "-000000000000.jpg", "0123456789abcdef0123456789abcdef01234567");
    }
}
