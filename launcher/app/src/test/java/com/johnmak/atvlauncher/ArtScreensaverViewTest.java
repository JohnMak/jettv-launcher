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
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Offline tests for {@link ArtScreensaverView}: the pure {@link ArtScreensaverView.Slideshow} coordinator driven
 * deterministically (no device, no BitmapFactory, no Views) over the streaming Wallhaven model. A fake {@link
 * ArtScreensaverView.Session} serves scripted search pages and image fetch/decode results; the single shared manual
 * executor models the pool; {@link FakeMain} runs posts inline and holds keyed delayed timers the test fires on demand.
 */
public class ArtScreensaverViewTest {
    private static final Object TOKEN = new Object(); // a fetched image handle

    // ---- pure helper --------------------------------------------------------------------------

    @Test public void shouldRunHonoursAggregateWindowVisibility() {
        assertTrue(ArtScreensaverView.shouldRun(true, true));
        assertFalse(ArtScreensaverView.shouldRun(true, false));
        assertFalse(ArtScreensaverView.shouldRun(false, true));
        assertFalse(ArtScreensaverView.shouldRun(false, false));
    }

    // ---- happy path ---------------------------------------------------------------------------

    @Test public void firstReadyImageRendersAndThenPrefetchesTheNext() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b", "c"))); // single page, three images
        h.slideshow.start();
        assertEquals(Arrays.asList(ArtPlayback.Phase.LOADING), h.phases);
        h.runNext();                        // page-1 fetch -> pager loaded -> decode(a) scheduled
        h.runNext();                        // fetch+decode(a) -> first image shown
        assertEquals(Arrays.asList(ArtPlayback.Phase.LOADING, ArtPlayback.Phase.RENDERING), h.phases);
        assertEquals("shown on layer 0", Integer.valueOf(h.pipeline.firstId), h.stage.layerBmp[0]);
        assertTrue("a display timer is armed for the hold", h.main.hasDelayed());
        assertEquals("the next image is being prefetched", 1, h.pending());
        assertEquals("one permit held for the shown bitmap", 1, h.budget());
    }

    @Test public void aTransitionFadesOutToBlackThenFadesTheNextInAndRecyclesTheOldInOrder() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b", "c")));
        h.slideshow.start();
        h.runNext();                        // page
        h.runNext();                        // decode(a) -> shown
        int a = h.stage.layerBmp[0];
        h.runNext();                        // decode(b) -> prefetched, waiting
        assertEquals("both permits held (shown + prefetched)", 0, h.budget());

        h.main.fireDelayed();               // the hold elapses -> FADE OUT begins
        assertTrue(h.stage.fadingOut());
        assertFalse(h.stage.fadingIn());
        assertFalse("A not recycled until fully black", h.stage.recycled.contains(a));

        h.stage.finishFadeOut();            // fully black -> outgoing cleared+recycled, next fades IN
        assertTrue("A recycled once fully black", h.stage.recycled.contains(a));
        assertNull("old layer cleared", h.stage.layerBmp[0]);
        assertEquals("outgoing permit freed at black", 1, h.budget());
        assertTrue("next image fading in from black", h.stage.fadingIn());

        h.stage.finishFadeIn();             // fully shown -> advance, re-arm the hold, prefetch the following
        assertTrue("display hold re-armed", h.main.hasDelayed());
        assertTrue("release order held throughout", h.stage.ok());
    }

    // ---- Ken-Burns zoom lifecycle (plan B7) ---------------------------------------------------

    @Test public void kenBurnsStartsWhenTheFirstImageBecomesCurrentAlignedToTheDwell() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();                        // page
        h.runNext();                        // decode(a) -> shown on layer 0 via showAsCurrent
        assertEquals("the zoom starts for the first image", 1, h.stage.kenBurnsStarts[0]);
        assertTrue("the zoom is running", h.stage.kenBurnsRunning[0]);
        assertEquals("the zoom duration is aligned to the 15s dwell", ArtPlayback.DISPLAY_MS, h.stage.kenBurnsDuration[0]);
        assertEquals("no zoom on the idle layer", 0, h.stage.kenBurnsStarts[1]);
    }

    @Test public void kenBurnsRestartsForASubsequentImageAtFadeInDoneOnTheNewLayer() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b", "c")));
        h.slideshow.start();
        h.runNext();                        // page
        h.runNext();                        // decode(a) -> shown on layer 0 (zoom started)
        h.runNext();                        // decode(b) -> prefetched
        h.main.fireDelayed();               // hold elapses -> fade out
        h.stage.finishFadeOut();            // -> black, next fades in on layer 1 (NOT started yet — waits for fade-in)
        assertEquals("the subsequent-image zoom is NOT started during the fade-in", 0, h.stage.kenBurnsStarts[1]);
        h.stage.finishFadeIn();             // onFadeInDone -> the subsequent image's zoom begins on the NEW layer
        assertEquals("the zoom starts on the new layer once faded in", 1, h.stage.kenBurnsStarts[1]);
        assertTrue(h.stage.kenBurnsRunning[1]);
        assertEquals(ArtPlayback.DISPLAY_MS, h.stage.kenBurnsDuration[1]);
    }

    @Test public void aTransitionCancelsTheOutgoingLayersZoomBeforeRecyclingItsBitmap() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();
        h.runNext();                        // a shown on layer 0, zoom running
        h.runNext();                        // b prefetched
        assertTrue(h.stage.kenBurnsRunning[0]);
        h.main.fireDelayed();               // fade out begins
        h.stage.finishFadeOut();            // fully black -> clear(layer0) cancels the zoom, THEN recycle
        assertFalse("the outgoing layer's zoom is cancelled at clear (before recycle)", h.stage.kenBurnsRunning[0]);
        assertTrue("release order still held (bitmap recycled after cancel+clear)", h.stage.ok());
    }

    @Test public void stopCancelsTheKenBurnsZoom() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();
        h.runNext();                        // a shown, zoom running
        assertTrue(h.stage.kenBurnsRunning[0]);
        h.slideshow.stop();
        assertFalse("stop cancels the zoom (via cancelAnimations)", h.stage.kenBurnsRunning[0]);
        assertFalse(h.stage.kenBurnsRunning[1]);
    }

    @Test public void teardownCancelsTheKenBurnsZoom() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();
        h.runNext();                        // a shown, zoom running
        h.slideshow.teardown();
        assertFalse("teardown cancels the zoom", h.stage.kenBurnsRunning[0]);
        assertFalse(h.stage.kenBurnsRunning[1]);
    }

    @Test public void aReusedLayerRestartsTheKenBurnsZoomFromScratch() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b", "c")));
        h.slideshow.start();
        h.runNext();                        // page
        h.runNext();                        // a -> layer 0 (starts[0]=1)
        h.runNext();                        // b prefetched
        h.main.fireDelayed();
        h.stage.finishFadeOut();
        h.stage.finishFadeIn();             // b -> layer 1 (starts[1]=1)
        h.runNext();                        // c prefetched
        h.main.fireDelayed();
        h.stage.finishFadeOut();
        h.stage.finishFadeIn();             // c -> layer 0 REUSED (starts[0] must become 2)
        assertEquals("the reused layer restarts the zoom for the fresh image", 2, h.stage.kenBurnsStarts[0]);
        assertTrue("and it is running for the fresh image", h.stage.kenBurnsRunning[0]);
    }

    @Test public void decodeWaitsForNonzeroBoundsThenRunsOnFirstLayout() {
        Harness h = new Harness();
        h.stage.w = 0;
        h.stage.h = 0;
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();                        // page -> scheduleDecode parks (no bounds)
        assertEquals("no decode before layout", 0, h.pending());
        h.stage.w = 1920;
        h.stage.h = 1080;
        h.slideshow.onBoundsReady();
        assertEquals("first decode runs once bounds exist", 1, h.pending());
        h.runNext();
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
    }

    @Test public void stopReleasesTheShownBitmapCancelsTheSessionAndStaysRestartable() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();
        h.runNext();                        // shown (A)
        int a = h.stage.layerBmp[0];
        h.slideshow.stop();
        assertTrue("shown bitmap released", h.stage.recycled.contains(a));
        assertNull(h.stage.layerBmp[0]);
        assertFalse("display timer cancelled", h.main.hasDelayed());
        assertEquals("session cancelled", 1, h.pipeline.cancels);
        assertEquals("permit freed", 2, h.budget());
        h.slideshow.start();
        assertTrue(h.slideshow.isRunning());
        assertEquals("a new session opened on restart", 2, h.pipeline.sessions.size());
    }

    // ---- M4 race: a late completion after replacement/teardown cannot assign -------------------

    @Test public void aDecodeCompletingAfterTeardownIsDiscardedNotAssigned() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();                        // page -> decode(a) scheduled
        h.slideshow.teardown();             // epoch bumped + session cancelled
        h.runNext();                        // decode(a) task: epoch pre-check skips it
        assertEquals("stale task admitted no fetch", 0, h.pipeline.fetches);
        assertNull(h.stage.layerBmp[0]);
        assertEquals("art never became visible", Arrays.asList(ArtPlayback.Phase.LOADING), h.phases);
        assertEquals("all permits free", 2, h.budget());
    }

    @Test public void aStalePageResultFromAnOldSessionIsDropped() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 3, "a")));
        h.slideshow.start();                // session A: queues the page fetch
        h.slideshow.stop();                 // epoch bumped — A's page result is now stale
        h.runNext();                        // A's page task runs, posts onPageResult(staleEpoch) -> dropped
        assertNull(h.stage.layerBmp[0]);
        assertFalse("nothing scheduled off a stale page", h.main.hasDelayed());
    }

    // ---- release order on teardown mid-transition ----------------------------------------------

    @Test public void teardownMidFadeOutReleasesBothLiveBitmapsWithoutRecyclingWhileReferenced() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b", "c")));
        h.slideshow.start();
        h.runNext();
        h.runNext();                        // shown (A)
        int a = h.stage.layerBmp[0];
        h.runNext();                        // prefetched (B)
        assertEquals(0, h.budget());
        h.main.fireDelayed();               // FADE OUT begins
        assertTrue(h.stage.fadingOut());
        h.slideshow.teardown();
        assertTrue("fading-out A released", h.stage.recycled.contains(a));
        assertEquals("both live bitmaps released", 2, h.stage.recycled.size());
        assertNull(h.stage.layerBmp[0]);
        assertNull(h.stage.layerBmp[1]);
        assertTrue(h.stage.ok());
        assertEquals("both permits freed", 2, h.budget());
    }

    // ---- black-hold load cover + long-load fallback + recovery --------------------------------

    @Test public void aLongLoadFallsBackToStarfieldThenRecoversWhenTheImageFinallyLoads() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.slideshow.start();
        h.runNext();                        // page
        h.runNext();                        // decode(a) -> shown; prefetch decode(b) scheduled, NOT run (stuck)
        h.main.fireDelayed();               // display hold -> FADE OUT
        h.stage.finishFadeOut();            // -> BLACK, holding (prefetch still stuck), black-hold armed
        assertTrue(h.main.hasDelayed());
        h.main.fireDelayed();               // BLACK-HOLD TIMEOUT -> fall back to starfield
        assertEquals("art hidden -> starfield (not endless black)", ArtPlayback.Phase.LOADING, last(h.phases));
        assertFalse(h.stage.fadingIn());
        h.runNext();                        // the stuck prefetch finally completes -> recovery
        assertEquals("recovered: image shown again", ArtPlayback.Phase.RENDERING, last(h.phases));
        assertNotNull(h.stage.layerBmp[0]);
        assertTrue("release order held", h.stage.ok());
        assertEquals("recovery image fades in from black", 0.0, h.stage.shownInnerStartAlpha, 1e-9);
    }

    // ---- permit never leaked on exception / null-decode / rejected handoff --------------------

    @Test public void aDecodeThatThrowsReleasesItsPermitAndSchedulesACleanRestart() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.pipeline.pages.add(ok(page("s2", 1, 1, "z"))); // the clean restart fetches a fresh seed
        h.slideshow.start();
        h.runNext();
        h.pipeline.lastSession.decodeThrowOnCall = 1;
        h.runNext();                        // fetch ok -> acquire -> decode THROWS -> error -> discard + cooldown
        assertEquals("permit released despite throw", ArtScreensaverView.MAX_LIVE_BITMAPS, h.budget());
        assertEquals("treated as an error, still LOADING (starfield)", ArtPlayback.Phase.LOADING, last(h.phases));
        assertEquals("in-flight discarded, decode parked on the cooldown (no immediate salvage)", 0, h.pending());
        assertTrue("a clean-restart cooldown is armed", h.main.hasDelayed());
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // deadline -> clean restart on a fresh seed -> renders
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
    }

    @Test public void aDecodeThatReturnsNullReleasesItsPermitAndSchedulesACleanRestart() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.pipeline.pages.add(ok(page("s2", 1, 1, "z")));
        h.slideshow.start();
        h.runNext();
        h.pipeline.lastSession.decodeNullOnCall = 1;
        h.runNext();
        assertEquals("permit released on null decode", ArtScreensaverView.MAX_LIVE_BITMAPS, h.budget());
        assertEquals(ArtPlayback.Phase.LOADING, last(h.phases));
        assertEquals("parked on the cooldown (no immediate salvage)", 0, h.pending());
        assertTrue(h.main.hasDelayed());
        h.pumpUntil(ArtPlayback.Phase.RENDERING);
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
    }

    @Test public void aRejectedBitmapHandoffPostReleasesThePermitAndRecyclesTheBitmap() {
        ManualExecutor worker = new ManualExecutor();
        java.util.concurrent.Semaphore budget = new java.util.concurrent.Semaphore(ArtScreensaverView.MAX_LIVE_BITMAPS);
        RejectingMain main = new RejectingMain(2); // post#1 onPageResult (runs); post#2 the bitmap handoff (rejected)
        FakePipeline pipeline = new FakePipeline();
        pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        FakeStage stage = new FakeStage();
        List<ArtPlayback.Phase> phases = new ArrayList<>();
        ArtScreensaverView.Slideshow<Integer> s =
                new ArtScreensaverView.Slideshow<>(pipeline, stage, worker, budget, main, new FakeClock(), phases::add);
        s.start();
        assertTrue(worker.runNext());   // page fetch -> post#1 onPageResult (accepted) -> schedule decode(a)
        assertTrue(worker.runNext());   // fetch -> acquire -> decode -> handoff post#2 REJECTED
        assertEquals("permit released when handoff rejected", ArtScreensaverView.MAX_LIVE_BITMAPS, budget.availablePermits());
        assertEquals("undelivered bitmap recycled (not leaked)", 1, stage.recycled.size());
        assertNull(stage.layerBmp[0]);
    }

    // ---- streaming pagination (refill across a page boundary, eviction) -----------------------

    @Test public void pagesRefillAcrossABoundaryAndTheConsumedPageIsEvicted() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 2, "a")));       // page 1: one image, more pages exist
        h.pipeline.pages.add(ok(page("s1", 2, 2, "b")));       // page 2 (refill, carries the seed)
        h.slideshow.start();
        h.runNext();                        // page-1 fetch -> loaded; prefetch page-2 AND decode(a) both scheduled
        h.drain();                          // run everything: decode(a) shows; page-2 refill loads; decode(b) prefetched
        assertEquals("page 2 carried the seed on refill", "s1", h.pipeline.lastPageSeed);
        assertEquals("the refill asked for page 2", 2, h.pipeline.lastPageNum);
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
        assertTrue("a is shown", h.stage.layerBmp[0] != null || h.stage.layerBmp[1] != null);
    }

    @Test public void anEmptyPageIsAnErrorThatCleanlyRestartsOnAFreshSeed() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 2)));            // page 1 valid-EMPTY (count==0 — an error, not terminal)
        h.pipeline.pages.add(ok(page("s2", 1, 1, "b")));       // the clean restart's fresh-seed page has an image
        h.slideshow.start();
        h.runNext();                        // empty page -> error -> discard + cooldown (NOT terminal)
        assertEquals("an empty page is an error, not exhaustion", ArtPlayback.Phase.LOADING, last(h.phases));
        assertTrue("a clean-restart cooldown is armed", h.main.hasDelayed());
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // deadline -> clean restart on a fresh seed -> renders
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
        assertEquals("the restart used a fresh seed", "", h.pipeline.lastPageSeed);
    }

    @Test public void anEmptyPrefetchRefillWhileTheCurrentPageHasImagesTriggersACleanRestart() {
        // Codex round-4 P2: an EMPTY prefetched refill (page 2) while the older page (page 1) still has images must NOT
        // be silently accepted — the incoming page's usable descriptors are classified, so zero routes to onError.
        Harness h = new Harness();
        h.stage.w = 0;                                        // not laid out yet -> nothing is shown (no display timer)
        h.stage.h = 0;
        h.pipeline.pages.add(ok(page("s1", 1, 3, "a", "b")));  // page 1: images AND more pages (lastPage 3)
        h.pipeline.pages.add(ok(page("s1", 2, 3)));            // page-2 REFILL is EMPTY (zero usable descriptors)
        h.pipeline.pages.add(ok(page("sFresh", 1, 1, "z")));   // the clean restart's fresh-seed page
        h.slideshow.start();
        h.runNext();                        // page 1 -> usable>0 -> decode parks (no bounds); refill page-2 scheduled
        h.runNext();                        // page-2 EMPTY refill -> routes to onError (NOT a silent accept)
        assertEquals("nothing shown yet (parked on bounds) — still LOADING", ArtPlayback.Phase.LOADING, last(h.phases));
        assertTrue("the empty refill routed to onError -> a clean-restart cooldown is armed", h.main.hasDelayed());
        assertEquals("onError fired (deadline = the error backoff), it was NOT silently accepted",
                ArtScreensaverView.RETRY_BASE_MS, h.main.lastDelayMs);
        h.stage.w = 1920;
        h.stage.h = 1080;
        h.advanceAndFire(ArtScreensaverView.RETRY_MAX_MS); // only the cooldownTick is pending -> clean restart
        h.drain();
        assertEquals("the restart used a FRESH seed (not page 3 of the old seed)", "", h.pipeline.lastPageSeed);
        assertEquals("and it renders", ArtPlayback.Phase.RENDERING, last(h.phases));
    }

    // ---- failure taxonomy: page-fetch failure retains the cursor + capped retry ---------------

    @Test public void aPageFetchFailureCleanlyRestartsOnAFreshSeed() {
        Harness h = new Harness();
        h.pipeline.pages.add(fail("503 the first time"));      // page fetch fails
        h.pipeline.pages.add(ok(page("s2", 1, 1, "a")));       // succeeds on the clean restart
        h.slideshow.start();
        h.runNext();                        // page fetch fails -> error -> discard + cooldown, NOT terminal
        assertEquals("still LOADING (starfield), not EXHAUSTED", ArtPlayback.Phase.LOADING, last(h.phases));
        assertTrue("a cancellable backoff cooldown is armed", h.main.hasDelayed());
        assertEquals("no image decoded yet", 0, h.pipeline.fetches);
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // deadline -> clean restart on a fresh seed -> renders
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
        assertEquals("the restart used a fresh seed", "", h.pipeline.lastPageSeed);
    }

    @Test public void a429SetsTheCooldownDeadlineToRetryAfterNeverSleepingAWorker() {
        Harness h = new Harness();
        h.pipeline.pages.add(http429(45_000));
        h.pipeline.pages.add(ok(page("s2", 1, 1, "a")));
        h.slideshow.start();
        h.runNext();                        // 429 -> cooldown deadline honouring Retry-After (no worker sleep)
        assertEquals("cooldown armed on the Handler at Retry-After", 45_000L, h.main.lastDelayMs);
        assertEquals(ArtPlayback.Phase.LOADING, last(h.phases));
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // the Retry-After cooldown elapses -> clean restart -> renders
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
    }

    @Test public void repeatedPageFetchFailuresSignalExhaustedButKeepCleanlyRestarting() {
        Harness h = new Harness();
        for (int i = 0; i <= ArtScreensaverView.MAX_ERROR_STREAK + 2; i++) h.pipeline.pages.add(fail("down"));
        h.slideshow.start();
        int guard = 0;
        while (last(h.phases) != ArtPlayback.Phase.EXHAUSTED && guard++ < 60) {
            if (h.main.hasDelayed()) h.advanceAndFire(ArtScreensaverView.RETRY_MAX_MS); // cooldown -> clean restart
            h.runNext();                                                                // ...which fails again
        }
        assertEquals("a persistent error run signals EXHAUSTED (starfield), never a busy loop",
                ArtPlayback.Phase.EXHAUSTED, last(h.phases));
        assertTrue("EXHAUSTED is non-terminal: a clean-restart cooldown is still armed", h.main.hasDelayed());
    }

    @Test public void repeatedImageFailuresSignalExhaustedViaTheSameCleanRestartPath() {
        Harness h = new Harness();
        for (int i = 0; i <= ArtScreensaverView.MAX_ERROR_STREAK + 2; i++) {
            h.pipeline.pages.add(ok(page("s" + i, 1, 1, "x" + i))); // each clean restart gets a fresh page...
            h.pipeline.availability.add(false);                      // ...whose image fetch fails
        }
        h.slideshow.start();
        int guard = 0;
        while (last(h.phases) != ArtPlayback.Phase.EXHAUSTED && guard++ < 80) {
            if (h.main.hasDelayed()) h.advanceAndFire(ArtScreensaverView.RETRY_MAX_MS);
            h.runNext();
        }
        assertEquals("image failures reach EXHAUSTED via the SAME clean-restart error path",
                ArtPlayback.Phase.EXHAUSTED, last(h.phases));
        assertNull("nothing was ever shown", h.stage.layerBmp[0]);
    }

    @Test public void exhaustedIsNonTerminalTheCleanRestartEventuallyReachesReady() {
        Harness h = new Harness();
        for (int i = 0; i <= ArtScreensaverView.MAX_ERROR_STREAK + 1; i++) h.pipeline.pages.add(fail("down"));
        h.pipeline.pages.add(ok(page("s9", 1, 1, "a")));   // the source comes back
        h.slideshow.start();
        int guard = 0;
        while (last(h.phases) != ArtPlayback.Phase.EXHAUSTED && guard++ < 60) {
            if (h.main.hasDelayed()) h.advanceAndFire(ArtScreensaverView.RETRY_MAX_MS);
            h.runNext();
        }
        assertEquals("a persistent error run hit EXHAUSTED", ArtPlayback.Phase.EXHAUSTED, last(h.phases));
        assertTrue("EXHAUSTED is NOT a dead stop — a clean-restart cooldown is armed", h.main.hasDelayed());
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // the clean restart eventually fetches a fresh seed and renders
        assertEquals("restored connectivity recovers to RENDERING", ArtPlayback.Phase.RENDERING, last(h.phases));
        assertEquals("every (re)start used a fresh seed", "", h.pipeline.lastPageSeed);
    }

    // ---- (a) the monotonic cooldown deadline is honoured at admission (no premature fetch) -----

    @Test public void aPage429HonoursRetryAfter120NotAShortBackoffAndRestartsCleanly() {
        Harness h = new Harness();
        for (int i = 0; i < 7; i++) h.pipeline.pages.add(http429(120_000)); // 7 page 429s, Retry-After:120s
        h.pipeline.pages.add(ok(page("s9", 1, 1, "a")));                    // then the source returns
        h.slideshow.start();
        h.runNext();                        // page#1 429 -> error -> cooldown deadline = 120s
        assertEquals("the deadline honours Retry-After:120, NOT a shorter (60s) backoff", 120_000L, h.main.lastDelayMs);
        h.clock.set(119_999);               // 1 ms before the deadline
        h.slideshow.onBoundsReady();
        assertEquals("no request admitted before the 120s deadline", 1, h.pipeline.pageCalls);
        assertEquals("in-flight discarded — nothing queued on the worker", 0, h.pending());
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // after the deadline: clean restart on a FRESH seed -> renders
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
        assertEquals("the restart used a fresh seed (no retained window)", "", h.pipeline.lastPageSeed);
    }

    // ---- (b) a CDN (image) 429 sets the deadline and cleanly restarts (no per-descriptor salvage) --

    @Test public void aCdn429SetsTheDeadlineAndCleanlyRestartsNoSalvage() {
        Harness h = new Harness();
        h.pipeline.pages.add(ok(page("s1", 1, 1, "a", "b")));
        h.pipeline.fetch429.add(60_000L);   // the first image fetch is rate-limited by the CDN
        h.pipeline.pages.add(ok(page("s2", 1, 1, "z"))); // the clean restart fetches a fresh seed
        h.slideshow.start();
        h.runNext();                        // page
        h.runNext();                        // fetch(a) -> 429 -> error -> discard + cooldown (no salvage of 'a')
        assertEquals("a CDN 429 does not blank/exhaust immediately — still LOADING", ArtPlayback.Phase.LOADING, last(h.phases));
        assertEquals("deadline set from Retry-After", 60_000L, h.main.lastDelayMs);
        assertEquals("no permit held by a cooldown", 2, h.budget());
        assertEquals("in-flight discarded — nothing queued", 0, h.pending());
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // deadline -> clean restart (fresh seed) -> 'z' renders
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
        assertEquals("restart used a fresh seed, not a salvage-retry of the 429'd descriptor", "", h.pipeline.lastPageSeed);
    }

    // ---- (c) recovery never leaves the in-flight flags stuck (the stream keeps flowing) --------

    @Test public void recoveryNeverLeavesInFlightFlagsStuck() {
        Harness h = new Harness();
        h.pipeline.pages.add(fail("down"));                    // first attempt errors
        h.pipeline.pages.add(ok(page("s2", 1, 1, "a", "b")));  // the clean restart's page has TWO images
        h.slideshow.start();
        h.runNext();                        // error -> discard + cooldown
        h.pumpUntil(ArtPlayback.Phase.RENDERING); // clean restart -> 'a' rendered
        assertEquals(ArtPlayback.Phase.RENDERING, last(h.phases));
        // if decodeInFlight had been left stuck true, showAsCurrent's prefetch would have been a no-op:
        assertEquals("after recovery the next image is actively prefetched (no stuck in-flight flag)", 1, h.pending());
    }

    // ---- generation invariant: an old-window work unit never touches the new window -----------

    @Test public void anOldGenerationDecodeResultIsDroppedAndNeverConsumesTheNewWindow() {
        // Posts are DEFERRED so the window can be renewed (generation bumped) while a decode result is in flight; the
        // stale-generation guard in onDecode then drops it. (An error-discard / clean restart bumps the SAME counter.)
        ManualExecutor worker = new ManualExecutor();
        java.util.concurrent.Semaphore budget = new java.util.concurrent.Semaphore(ArtScreensaverView.MAX_LIVE_BITMAPS);
        QueuingMain main = new QueuingMain();
        FakePipeline pipeline = new FakePipeline();
        pipeline.pages.add(ok(page("s1", 1, 1, "a"))); // window 1
        FakeStage stage = new FakeStage();
        List<ArtPlayback.Phase> phases = new ArrayList<>();
        ArtScreensaverView.Slideshow<Integer> s =
                new ArtScreensaverView.Slideshow<>(pipeline, stage, worker, budget, main, new FakeClock(), phases::add);
        s.start();                          // generation g1
        assertTrue(worker.runNext());       // page task -> post onPageResult (DEFERRED)
        assertTrue(main.runNextPost());     // onPageResult -> pager=[a]; decode(a) task queued (captures g1)
        assertTrue(worker.runNext());       // decode(a): fetch+decode -> post onDecode(g1, bmpA) DEFERRED
        assertEquals("a permit is held for the in-flight decode", 1, budget.availablePermits());
        s.stop();                           // the window is torn down (generation bumped) before the result is applied
        s.start();                          // a NEW window/generation
        assertTrue(main.runNextPost());     // onDecode(g1, bmpA): stale generation -> DROPPED, never assigned
        assertNull("the stale bitmap was never assigned to the new window", stage.layerBmp[0]);
        assertNull(stage.layerBmp[1]);
        assertEquals("the stale bitmap was recycled, not leaked", 1, stage.recycled.size());
        assertEquals("its permit was released", ArtScreensaverView.MAX_LIVE_BITMAPS, budget.availablePermits());
    }

    // ---- harness ------------------------------------------------------------------------------

    private static ArtPlayback.Phase last(List<ArtPlayback.Phase> phases) {
        return phases.get(phases.size() - 1);
    }

    private static WallhavenImage wimg(String id) {
        return new WallhavenImage(id, "https://w.wallhaven.cc/full/x/" + id + ".jpg", "3840x2160");
    }

    private static WallhavenSearch.Page page(String seed, int page, int lastPage, String... ids) {
        List<WallhavenImage> l = new ArrayList<>();
        for (String id : ids) l.add(wimg(id));
        return new WallhavenSearch.Page(l, seed, page, lastPage);
    }

    private static FetchResult<WallhavenSearch.Page> ok(WallhavenSearch.Page p) {
        return FetchResult.ok(p);
    }

    private static FetchResult<WallhavenSearch.Page> fail(String msg) {
        return FetchResult.err(msg);
    }

    private static FetchResult<WallhavenSearch.Page> http429(long retryAfterMs) {
        return FetchResult.err("HTTP 429", new WallhavenSource.HttpStatus(429, retryAfterMs));
    }

    private static final class Harness {
        final FakePipeline pipeline = new FakePipeline();
        final FakeStage stage = new FakeStage();
        final ManualExecutor worker = new ManualExecutor();
        final java.util.concurrent.Semaphore budget =
                new java.util.concurrent.Semaphore(ArtScreensaverView.MAX_LIVE_BITMAPS);
        final FakeMain main = new FakeMain();
        final FakeClock clock = new FakeClock();
        final List<ArtPlayback.Phase> phases = new ArrayList<>();
        final ArtScreensaverView.Slideshow<Integer> slideshow =
                new ArtScreensaverView.Slideshow<>(pipeline, stage, worker, budget, main, clock, phases::add);

        boolean runNext() {
            return worker.runNext();
        }

        int pending() {
            return worker.pending();
        }

        int budget() {
            return budget.availablePermits();
        }

        void drain() {
            int guard = 0;
            while (worker.runNext() && guard++ < 200) { /* run the shared queue to completion */ }
        }

        /** Advance time past any armed cooldown/recovery timer and fire the oldest pending timer (then run nothing). */
        void advanceAndFire(long ms) {
            clock.advance(ms);
            main.fireDelayed();
        }

        /** Drive timers (advancing the clock) + worker tasks until {@code target} phase is reached or a bound trips. */
        void pumpUntil(ArtPlayback.Phase target) {
            int guard = 0;
            while (last(phases) != target && guard++ < 80) {
                if (main.hasDelayed()) {
                    clock.advance(ArtScreensaverView.RETRY_MAX_MS);
                    main.fireDelayed();
                }
                worker.runNext();
            }
        }
    }

    private static final class FakeClock implements ArtScreensaverView.Clock {
        long now;

        @Override public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }

        void set(long ms) {
            now = ms;
        }
    }

    /** A controllable session factory; aggregate counters are shared so tests read them off the pipeline. */
    private static final class FakePipeline implements ArtScreensaverView.Pipeline<Integer> {
        final Deque<FetchResult<WallhavenSearch.Page>> pages = new ArrayDeque<>();
        final Deque<Boolean> availability = new ArrayDeque<>(); // polled per image fetch; empty = success
        final Deque<Long> fetch429 = new ArrayDeque<>();        // polled first per image fetch; a value = CDN 429 (ms)
        final Set<Integer> handedOut = new HashSet<>();
        final List<FakeSession> sessions = new ArrayList<>();
        int pageCalls;
        int fetches;
        int cancels;
        int nextId = 100;
        int firstId = -1;
        String lastPageSeed;
        int lastPageNum;
        FakeSession lastSession;

        @Override public ArtScreensaverView.Session<Integer> open() {
            FakeSession s = new FakeSession(this);
            sessions.add(s);
            lastSession = s;
            return s;
        }
    }

    private static final class FakeSession implements ArtScreensaverView.Session<Integer> {
        private final FakePipeline p;
        volatile boolean cancelled;
        int decodeThrowOnCall;
        int decodeNullOnCall;
        private int decodeCalls;

        FakeSession(FakePipeline p) {
            this.p = p;
        }

        @Override public FetchResult<WallhavenSearch.Page> page(String seed, int page) {
            p.pageCalls++;
            p.lastPageSeed = seed;
            p.lastPageNum = page;
            if (cancelled) return FetchResult.err("cancelled");
            FetchResult<WallhavenSearch.Page> r = p.pages.poll();
            return r != null ? r : FetchResult.ok(new WallhavenSearch.Page(new ArrayList<>(), seed, page, page));
        }

        @Override public FetchResult<Object> fetch(WallhavenImage image) {
            if (cancelled) return FetchResult.err("cancelled");
            Long retryAfter = p.fetch429.poll();
            if (retryAfter != null) {
                return FetchResult.err("HTTP 429", new WallhavenSource.HttpStatus(429, retryAfter));
            }
            Boolean ok = p.availability.poll();
            if (ok != null && !ok) return FetchResult.err("unavailable");
            p.fetches++;
            return FetchResult.ok((Object) TOKEN);
        }

        @Override public Integer decode(Object fetched, int targetW, int targetH) {
            int call = ++decodeCalls;
            if (call == decodeThrowOnCall) throw new RuntimeException("decode boom");
            if (call == decodeNullOnCall) return null;
            int id = p.nextId++;
            if (p.firstId < 0) p.firstId = id;
            p.handedOut.add(id);
            return id;
        }

        @Override public void cancel() {
            cancelled = true;
            p.cancels++;
        }
    }

    private static final class FakeStage implements ArtScreensaverView.Stage<Integer> {
        int w = 1920;
        int h = 1080;
        final Integer[] layerBmp = new Integer[2];
        final Set<Integer> recycled = new HashSet<>();
        double shownInnerStartAlpha = -1;
        // Ken-Burns zoom lifecycle (plan B7): per-layer start count, last duration, and whether a zoom is "running"
        // (started and not yet cancelled). clear()/cancelAnimations() cancel it exactly as the real ViewStage does.
        final int[] kenBurnsStarts = new int[2];
        final long[] kenBurnsDuration = {-1, -1};
        final boolean[] kenBurnsRunning = new boolean[2];
        private Runnable fadeOutEnd;
        private Runnable fadeInEnd;
        private boolean violated;

        @Override public void fadeOutToBlack(int layer, long ms, Runnable onEnd) {
            fadeOutEnd = onEnd;
        }

        @Override public void fadeInFromBlack(int layer, Integer bitmap, String caption, long ms, Runnable onEnd) {
            layerBmp[layer] = bitmap;
            shownInnerStartAlpha = 0.0;
            fadeInEnd = onEnd;
        }

        @Override public void startKenBurns(int layer, long durationMs) {
            kenBurnsStarts[layer]++;
            kenBurnsDuration[layer] = durationMs;
            kenBurnsRunning[layer] = true;
        }

        @Override public void clear(int layer) {
            layerBmp[layer] = null;
            kenBurnsRunning[layer] = false; // the real ViewStage cancels the zoom before clearing the drawable
        }

        @Override public void recycle(Integer bitmap) {
            if (bitmap.equals(layerBmp[0]) || bitmap.equals(layerBmp[1])) violated = true;
            recycled.add(bitmap);
        }

        @Override public void cancelAnimations() {
            fadeOutEnd = null;
            fadeInEnd = null;
            kenBurnsRunning[0] = false;
            kenBurnsRunning[1] = false;
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

    /** A single shared manual executor (models the shared pool): tasks run when pulled. */
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

    /** A main that runs posts inline (deterministic) and holds keyed delayed timers the test fires on demand. */
    private static final class FakeMain implements ArtScreensaverView.Main {
        private final Map<Runnable, Long> delayed = new LinkedHashMap<>();
        long lastDelayMs = -1;

        @Override public boolean post(Runnable r) {
            r.run();
            return true;
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delayed.put(r, delayMs);
            lastDelayMs = delayMs;
        }

        @Override public void cancel(Runnable r) {
            delayed.remove(r);
        }

        boolean hasDelayed() {
            return !delayed.isEmpty();
        }

        /** Fire the oldest pending timer (tests keep at most one relevant timer pending at a time). */
        void fireDelayed() {
            assertFalse("expected a pending delayed task", delayed.isEmpty());
            Runnable r = delayed.keySet().iterator().next();
            delayed.remove(r);
            r.run();
        }
    }

    /** A main that DEFERS posts (the test drains them) so state can change between a post and its application. */
    private static final class QueuingMain implements ArtScreensaverView.Main {
        private final Deque<Runnable> posts = new ArrayDeque<>();
        private final Map<Runnable, Long> delayed = new LinkedHashMap<>();

        @Override public boolean post(Runnable r) {
            posts.add(r);
            return true;
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delayed.put(r, delayMs);
        }

        @Override public void cancel(Runnable r) {
            delayed.remove(r);
        }

        boolean runNextPost() {
            Runnable r = posts.poll();
            if (r == null) return false;
            r.run();
            return true;
        }
    }

    /** A main that runs posts inline but REJECTS the {@code rejectAt}-th post (dead looper). */
    private static final class RejectingMain implements ArtScreensaverView.Main {
        private final int rejectAt;
        private int count;
        private final Map<Runnable, Long> delayed = new LinkedHashMap<>();

        RejectingMain(int rejectAt) {
            this.rejectAt = rejectAt;
        }

        @Override public boolean post(Runnable r) {
            if (++count == rejectAt) return false;
            r.run();
            return true;
        }

        @Override public void postDelayed(Runnable r, long delayMs) {
            delayed.put(r, delayMs);
        }

        @Override public void cancel(Runnable r) {
            delayed.remove(r);
        }
    }
}
