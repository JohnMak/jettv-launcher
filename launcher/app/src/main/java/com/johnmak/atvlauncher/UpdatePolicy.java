package com.johnmak.atvlauncher;

/** Pure scheduling rules of the updater: when to look for a release and when a ready one may install itself. */
final class UpdatePolicy {
    enum Trigger {
        /** The first evaluation after the process started. */
        PROCESS_START,
        /** The periodic evaluation while the process lives. */
        TICK
    }

    static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    static final long AUTO_INSTALL_IDLE_MS = 10L * 60 * 1000;

    private UpdatePolicy() {}

    /** A check starts when nothing is in flight or held, and it is process start or 6 h since the last good answer. */
    static boolean shouldCheck(UpdateState.Kind state, Trigger trigger, long lastCheckAtMs, long nowMs) {
        if (state != UpdateState.Kind.IDLE && state != UpdateState.Kind.UP_TO_DATE && state != UpdateState.Kind.ERROR) {
            return false;
        }
        if (trigger == Trigger.PROCESS_START) return true;
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
