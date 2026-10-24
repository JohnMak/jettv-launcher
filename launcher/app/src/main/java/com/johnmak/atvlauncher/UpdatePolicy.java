package com.johnmak.atvlauncher;

/** Pure scheduling rules of the updater: when to look for a release and when a ready one may install itself. */
final class UpdatePolicy {
    enum Trigger {
        /** The first evaluation after the process started. */
        PROCESS_START,
        /** The periodic evaluation while the process lives. */
        TICK,
        /** An evaluation inside the boot window that has not yet got a definite answer: checks, then installs at once. */
        BOOT
    }

    static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    static final long AUTO_INSTALL_IDLE_MS = 10L * 60 * 1000;

    /** A process that starts this soon after the device booted is a boot start. */
    static final long BOOT_WINDOW_MS = 10L * 60 * 1000;
    /** Tick interval while a boot check still waits for the network. */
    static final long BOOT_RETRY_MS = 30L * 1000;
    static final long TICK_MS = 10L * 60 * 1000;

    private UpdatePolicy() {}

    /** True when the process started within the boot window after the device came up. */
    static boolean isBootStart(long elapsedRealtimeMs) {
        return elapsedRealtimeMs >= 0 && elapsedRealtimeMs < BOOT_WINDOW_MS;
    }

    /** True while the boot window of a boot start (ending at {@code bootUntilMs}, 0 = not a boot start) is open. */
    static boolean bootActive(long nowMs, long bootUntilMs) {
        return bootUntilMs > 0 && nowMs < bootUntilMs;
    }

    /** The boot check is finished by any answer except a network failure, which is retried within the window. */
    static boolean bootAnswered(UpdateState.Kind state, UpdateError error) {
        switch (state) {
            case UP_TO_DATE:
            case READY:
            case INSTALLING:
                return true;
            case ERROR:
                return error != UpdateError.NETWORK;
            default:
                return false;
        }
    }

    /** Delay to the next evaluation: short while a boot check retries, otherwise the regular tick. */
    static long nextTickDelayMs(boolean bootRetrying) {
        return bootRetrying ? BOOT_RETRY_MS : TICK_MS;
    }

    /** A check starts when nothing is in flight or held, and it is process start or 6 h since the last good answer. */
    static boolean shouldCheck(UpdateState.Kind state, Trigger trigger, long lastCheckAtMs, long nowMs) {
        if (state != UpdateState.Kind.IDLE && state != UpdateState.Kind.UP_TO_DATE && state != UpdateState.Kind.ERROR) {
            return false;
        }
        if (trigger == Trigger.PROCESS_START || trigger == Trigger.BOOT) return true;
        return lastCheckAtMs <= 0 || nowMs < lastCheckAtMs || nowMs - lastCheckAtMs >= CHECK_INTERVAL_MS;
    }

    /**
     * A verified update installs itself while the home screen is up and untouched for 10 min, or while a screensaver
     * (in-launcher or system dream) is showing.
     */
    static boolean shouldAutoInstall(UpdateState.Kind state, boolean launcherForeground, long idleMs,
                                     boolean screensaverShowing) {
        if (state != UpdateState.Kind.READY) return false;
        return screensaverShowing || (launcherForeground && idleMs >= AUTO_INSTALL_IDLE_MS);
    }
}
