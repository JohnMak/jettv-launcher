package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class ArtPlaybackTest {
    @Test public void indexWraps() {
        assertEquals(1, ArtPlayback.nextIndex(0, 3));
        assertEquals(0, ArtPlayback.nextIndex(2, 3));
        // single-image queue holds on the same index (no crossfade target ever changes)
        assertEquals(0, ArtPlayback.nextIndex(0, 1));
    }

    @Test public void nextIndexRejectsAnEmptyQueue() {
        try {
            ArtPlayback.nextIndex(0, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test public void crossfadeStartsBeforeTheEnd() {
        assertEquals(ArtPlayback.DISPLAY_MS - ArtPlayback.CROSSFADE_MS, ArtPlayback.crossfadeStartMs(ArtPlayback.DISPLAY_MS));
        assertEquals(13_500, ArtPlayback.crossfadeStartMs(15_000));
        assertEquals(0, ArtPlayback.crossfadeStartMs(1_000)); // clamped, never negative
        assertEquals(0, ArtPlayback.crossfadeStartMs(0));
    }

    @Test public void emptyQueueIsExhaustedImmediately() {
        assertEquals(ArtPlayback.Phase.EXHAUSTED, ArtPlayback.initialPhase(0));
        assertEquals(ArtPlayback.Phase.LOADING, ArtPlayback.initialPhase(1));
        // defensive: nextPhase itself also refuses to leave EXHAUSTED for a zero-size queue, regardless of event
        assertEquals(ArtPlayback.Phase.EXHAUSTED,
                ArtPlayback.nextPhase(ArtPlayback.Phase.LOADING, ArtPlayback.Event.READY, 0, 0));
    }

    @Test public void loadingMovesToRenderingOnlyOnFirstReady() {
        ArtPlayback.Phase loading = ArtPlayback.initialPhase(3);
        assertEquals(ArtPlayback.Phase.LOADING, loading);
        int streak = ArtPlayback.nextFailureStreak(0, ArtPlayback.Event.READY);
        assertEquals(0, streak);
        assertEquals(ArtPlayback.Phase.RENDERING,
                ArtPlayback.nextPhase(loading, ArtPlayback.Event.READY, streak, 3));
    }

    @Test public void itemUnavailableAdvancesButStaysLoadingUntilSomeImageIsReady() {
        ArtPlayback.Phase phase = ArtPlayback.Phase.LOADING;
        int streak = 0;
        // two failures out of a three-item queue: index moves on, but phase stays LOADING (never rendered yet)
        for (int i = 0; i < 2; i++) {
            streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.ITEM_UNAVAILABLE);
            phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.ITEM_UNAVAILABLE, streak, 3);
            assertEquals(ArtPlayback.Phase.LOADING, phase);
        }
    }

    @Test public void readyThenItemUnavailableKeepsRenderingAndCurrentImage() {
        int streak = ArtPlayback.nextFailureStreak(0, ArtPlayback.Event.READY);
        ArtPlayback.Phase phase = ArtPlayback.nextPhase(ArtPlayback.Phase.LOADING, ArtPlayback.Event.READY, streak, 3);
        assertEquals(ArtPlayback.Phase.RENDERING, phase);

        streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.ITEM_UNAVAILABLE);
        phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.ITEM_UNAVAILABLE, streak, 3);
        // the last good image stays on screen; the engine does not blank on a single bad item
        assertEquals(ArtPlayback.Phase.RENDERING, phase);
    }

    @Test public void failureCounterResetsAfterASuccessSoASlowLossyPoolNeverFalselyExhausts() {
        int count = 2;
        ArtPlayback.Phase phase = ArtPlayback.initialPhase(count);
        int streak = 0;
        // alternate fail/succeed many times — streak never reaches `count` because every READY resets it
        for (int i = 0; i < 20; i++) {
            ArtPlayback.Event event = (i % 2 == 0) ? ArtPlayback.Event.ITEM_UNAVAILABLE : ArtPlayback.Event.READY;
            streak = ArtPlayback.nextFailureStreak(streak, event);
            phase = ArtPlayback.nextPhase(phase, event, streak, count);
            assertNotEquals(ArtPlayback.Phase.EXHAUSTED, phase);
        }
    }

    @Test public void fullFailingPassExhausts() {
        int count = 3;
        ArtPlayback.Phase phase = ArtPlayback.initialPhase(count);
        int streak = 0;
        for (int i = 0; i < count - 1; i++) {
            streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.ITEM_UNAVAILABLE);
            phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.ITEM_UNAVAILABLE, streak, count);
            assertEquals(ArtPlayback.Phase.LOADING, phase); // not yet a full pass
        }
        streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.ITEM_UNAVAILABLE);
        phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.ITEM_UNAVAILABLE, streak, count);
        assertEquals(ArtPlayback.Phase.EXHAUSTED, phase); // every entry attempted once, zero READY
    }

    @Test public void exhaustedIsTerminalUntilTheNextPrepare() {
        ArtPlayback.Phase exhausted = ArtPlayback.Phase.EXHAUSTED;
        // a late READY after exhaustion does not resurrect the slideshow
        assertEquals(ArtPlayback.Phase.EXHAUSTED,
                ArtPlayback.nextPhase(exhausted, ArtPlayback.Event.READY, 0, 3));
        assertEquals(ArtPlayback.Phase.EXHAUSTED,
                ArtPlayback.nextPhase(exhausted, ArtPlayback.Event.ITEM_UNAVAILABLE, 1, 3));
        // only a fresh `initialPhase` (the next `prepare`) leaves EXHAUSTED
        assertEquals(ArtPlayback.Phase.LOADING, ArtPlayback.initialPhase(3));
    }

    @Test public void singleImageQueueHoldsWithoutCrossfadingToItself() {
        int count = 1;
        ArtPlayback.Phase phase = ArtPlayback.initialPhase(count);
        int streak = ArtPlayback.nextFailureStreak(0, ArtPlayback.Event.READY);
        phase = ArtPlayback.nextPhase(phase, ArtPlayback.Event.READY, streak, count);
        assertEquals(ArtPlayback.Phase.RENDERING, phase);
        // the only index there is, forever — the caller skips the crossfade when the target index is unchanged
        assertEquals(0, ArtPlayback.nextIndex(0, count));
    }
}
