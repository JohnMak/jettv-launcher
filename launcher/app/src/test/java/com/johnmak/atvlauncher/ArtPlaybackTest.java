package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class ArtPlaybackTest {
    @Test public void indexWraps() {
        assertEquals(1, ArtPlayback.nextIndex(0, 3));
        assertEquals(0, ArtPlayback.nextIndex(2, 3));
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
        assertEquals(0, ArtPlayback.crossfadeStartMs(1_000));
        assertEquals(0, ArtPlayback.crossfadeStartMs(0));
    }

    @Test public void readyAlwaysMovesToRendering() {
        assertEquals(ArtPlayback.Phase.RENDERING,
                ArtPlayback.nextPhase(ArtPlayback.Phase.LOADING, ArtPlayback.Event.READY));
        assertEquals(ArtPlayback.Phase.RENDERING,
                ArtPlayback.nextPhase(ArtPlayback.Phase.RENDERING, ArtPlayback.Event.READY));
    }

    @Test public void itemUnavailableKeepsTheCurrentPhaseNeverBlanks() {
        // a single bad item never changes the phase — the last good image stays shown / the starfield stays
        assertEquals(ArtPlayback.Phase.LOADING,
                ArtPlayback.nextPhase(ArtPlayback.Phase.LOADING, ArtPlayback.Event.ITEM_UNAVAILABLE));
        assertEquals(ArtPlayback.Phase.RENDERING,
                ArtPlayback.nextPhase(ArtPlayback.Phase.RENDERING, ArtPlayback.Event.ITEM_UNAVAILABLE));
    }

    @Test public void exhaustedIsNonTerminalAndRecoversOnlyOnAReadyBitmap() {
        // plan B6: EXHAUSTED is NO LONGER terminal — a decoded bitmap (READY) recovers to RENDERING...
        assertEquals(ArtPlayback.Phase.RENDERING,
                ArtPlayback.nextPhase(ArtPlayback.Phase.EXHAUSTED, ArtPlayback.Event.READY));
        // ...but a mere item-unavailable (no decoded bitmap) does NOT leave EXHAUSTED
        assertEquals(ArtPlayback.Phase.EXHAUSTED,
                ArtPlayback.nextPhase(ArtPlayback.Phase.EXHAUSTED, ArtPlayback.Event.ITEM_UNAVAILABLE));
    }

    @Test public void failureStreakResetsOnlyOnAReadyBitmap() {
        int streak = 0;
        streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.ITEM_UNAVAILABLE);
        streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.ITEM_UNAVAILABLE);
        assertEquals("consecutive failures accumulate", 2, streak);
        streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.READY);
        assertEquals("a decoded bitmap resets the streak", 0, streak);
        streak = ArtPlayback.nextFailureStreak(streak, ArtPlayback.Event.ITEM_UNAVAILABLE);
        assertEquals(1, streak);
    }

    @Test public void singleImageQueueHoldsWithoutCrossfadingToItself() {
        assertEquals(ArtPlayback.Phase.RENDERING,
                ArtPlayback.nextPhase(ArtPlayback.Phase.LOADING, ArtPlayback.Event.READY));
        assertEquals(0, ArtPlayback.nextIndex(0, 1));
    }
}
