package com.johnmak.atvlauncher;

/**
 * Pure idle stages by time since the last input: ACTIVE, then DIM and SCREENSAVER at the delays of the
 * {@link LauncherSettings}. A stage with delay 0 (Off) never occurs; a dim that would start at or after the screensaver
 * is overtaken by it.
 */
final class IdleTimeline {
    enum Stage { ACTIVE, DIM, SCREENSAVER }

    private static final long NEVER = Long.MAX_VALUE;

    private final long dimAfterMs;
    private final long screensaverAfterMs;

    IdleTimeline(LauncherSettings settings) {
        dimAfterMs = settings.dimDelaySec == 0 ? NEVER : settings.dimDelayMillis();
        screensaverAfterMs = settings.screensaverDelayMin == 0 ? NEVER : settings.screensaverDelayMillis();
    }

    Stage at(long idleMs) {
        if (idleMs >= screensaverAfterMs) return Stage.SCREENSAVER;
        if (idleMs >= dimAfterMs) return Stage.DIM;
        return Stage.ACTIVE;
    }

    /** Milliseconds until the stage changes, or -1 when no later stage exists. */
    long untilNextStage(long idleMs) {
        long next = NEVER;
        if (dimAfterMs > idleMs && dimAfterMs < screensaverAfterMs) next = dimAfterMs;
        else if (screensaverAfterMs > idleMs) next = screensaverAfterMs;
        return next == NEVER ? -1 : next - idleMs;
    }
}
