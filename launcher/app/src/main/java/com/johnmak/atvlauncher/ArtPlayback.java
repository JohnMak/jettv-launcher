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

    /** Lifecycle of one screensaver session. EXHAUSTED is final until the next {@code prepare}. */
    enum Phase { LOADING, RENDERING, EXHAUSTED }

    /** {@code ITEM_UNAVAILABLE} covers both a fetch failure and a decode failure — the phase engine treats them
     * identically: advance past the item, never blank the current image. */
    enum Event { READY, ITEM_UNAVAILABLE }

    private ArtPlayback() {}

    /** The phase a freshly-built queue starts in: {@code EXHAUSTED} immediately for an empty pool (no entry to
     * even attempt), {@code LOADING} otherwise. */
    static Phase initialPhase(int count) {
        return count <= 0 ? Phase.EXHAUSTED : Phase.LOADING;
    }

    /**
     * The consecutive-failure streak AFTER {@code event}: reset to 0 on {@link Event#READY}, incremented on
     * {@link Event#ITEM_UNAVAILABLE}. The caller computes this first and passes the result into
     * {@link #nextPhase} — a slow but eventually-successful pool never falsely exhausts, because any single
     * success resets the count towards a full failing pass back to zero.
     */
    static int nextFailureStreak(int streak, Event event) {
        return event == Event.READY ? 0 : streak + 1;
    }

    /**
     * Next phase after {@code event}, given the failure streak AFTER this event ({@link #nextFailureStreak})
     * and the queue size. {@code EXHAUSTED} is terminal — no event resurrects it, and an empty queue is always
     * {@code EXHAUSTED}. A {@code READY} always moves to (or stays in) {@code RENDERING}. An
     * {@code ITEM_UNAVAILABLE} keeps the CURRENT phase — {@code LOADING} until some image has ever been ready,
     * {@code RENDERING} with the last good image still shown otherwise — unless the streak has now covered
     * every queue entry once with no {@code READY}, in which case the pass is exhausted.
     */
    static Phase nextPhase(Phase current, Event event, int failureStreakAfterEvent, int count) {
        if (current == Phase.EXHAUSTED || count <= 0) return Phase.EXHAUSTED;
        if (event == Event.READY) return Phase.RENDERING;
        return failureStreakAfterEvent >= count ? Phase.EXHAUSTED : current;
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
