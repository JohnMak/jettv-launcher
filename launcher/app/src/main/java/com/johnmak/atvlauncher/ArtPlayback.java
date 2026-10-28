package com.johnmak.atvlauncher;

/**
 * Pure timing and phase-cycling logic of the ART paintings screensaver (the {@link VideoPlayback} precedent,
 * adapted for a slideshow of independently fetched images rather than a single stream).
 *
 * <p>Unlike {@link VideoPlayback}'s {@code GAVE_UP} (final on the FIRST failure), a single bad painting must
 * never blank the screen or give up: {@link Event#ITEM_UNAVAILABLE} (fetch or decode failure alike) advances
 * past the item but keeps whatever is currently shown. Only a full pass with zero successes — every entry in
 * the queue attempted once with no {@link Event#READY} in between — exhausts the pool. The caller tracks the
 * consecutive-failure streak (reset on every {@code READY}, see {@link #nextFailureStreak}) and the phase
 * (see {@link #nextPhase}) as plain fields, exactly as {@link PlaylistVideoView} holds {@link VideoPlayback.Phase}.
 */
final class ArtPlayback {
    /** How long one painting stays on screen before the crossfade into the next one starts. */
    static final long DISPLAY_MS = 15_000;
    /** Crossfade duration between two consecutive paintings. */
    static final long CROSSFADE_MS = 1500;

    /** Lifecycle of one screensaver session. Since the Wallhaven rework (plan B6) EXHAUSTED is NON-terminal: it is
     * entered when the background retry policy gives up (nothing renderable), yet a later decoded bitmap reaching
     * {@link Event#READY} recovers to {@link #RENDERING} — the screensaver never permanently blanks within a session. */
    enum Phase { LOADING, RENDERING, EXHAUSTED }

    /** {@code ITEM_UNAVAILABLE} covers both a fetch failure and a decode failure — the phase engine treats them
     * identically: advance past the item, never blank the current image. */
    enum Event { READY, ITEM_UNAVAILABLE }

    private ArtPlayback() {}

    /**
     * The consecutive item-failure streak AFTER {@code event}: reset to 0 on {@link Event#READY}, incremented on
     * {@link Event#ITEM_UNAVAILABLE}. The coordinator uses this to bound a run of decode/descriptor failures (plan
     * B6's "unsuccessful pass"); any single {@code READY} resets it — recovery and streak-reset both REQUIRE a
     * decoded bitmap, never a mere HTTP/search success.
     */
    static int nextFailureStreak(int streak, Event event) {
        return event == Event.READY ? 0 : streak + 1;
    }

    /**
     * Next phase after {@code event} (plan B6 — EXHAUSTED is non-terminal). A {@code READY} ALWAYS moves to (or stays
     * in) {@code RENDERING} — including recovering OUT of {@code EXHAUSTED} the instant a decoded bitmap arrives. An
     * {@code ITEM_UNAVAILABLE} keeps the CURRENT phase (the last good image stays shown; a single bad item never
     * blanks). Entering {@code EXHAUSTED} (the background retry giving up) is a separate, explicit act of the
     * coordinator, NOT computed here from any cycle/queue count.
     */
    static Phase nextPhase(Phase current, Event event) {
        return event == Event.READY ? Phase.RENDERING : current;
    }

    /** Next queue index, cyclic; a single-item queue always holds on index 0 (no crossfade target changes). */
    static int nextIndex(int index, int count) {
        if (count <= 0) throw new IllegalArgumentException("count");
        return (index + 1) % count;
    }

    /** Position (ms into the current image's display window) at which the crossfade into the next image starts. */
    static long crossfadeStartMs(long displayMs) {
        return Math.max(0, displayMs - CROSSFADE_MS);
    }
}
