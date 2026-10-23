package com.johnmak.atvlauncher;

import android.os.SystemClock;

/**
 * Process-wide, main-thread-only view of what the user is looking at, read by {@link UpdateController} to find a
 * quiet moment: whether a screensaver (in-launcher or system dream) is up, and how long the home screen has been idle.
 */
final class ScreensaverState {
    private static boolean inLauncher;
    private static boolean dream;
    private static boolean launcherForeground;
    private static long lastInputUptime;

    private ScreensaverState() {}

    static void setInLauncher(boolean showing) { inLauncher = showing; }

    static void setDream(boolean showing) { dream = showing; }

    static boolean showing() { return inLauncher || dream; }

    static void launcherStarted(long uptimeMs) {
        launcherForeground = true;
        lastInputUptime = uptimeMs;
    }

    static void launcherInput(long uptimeMs) { lastInputUptime = uptimeMs; }

    static void launcherStopped() { launcherForeground = false; }

    static boolean launcherForeground() { return launcherForeground; }

    /** Time since the last remote input; meaningful only while {@link #launcherForeground}. */
    static long launcherIdleMs() { return SystemClock.uptimeMillis() - lastInputUptime; }
}
