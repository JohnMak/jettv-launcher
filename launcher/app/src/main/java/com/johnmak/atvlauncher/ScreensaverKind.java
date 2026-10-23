package com.johnmak.atvlauncher;

/**
 * What is actually visible behind the screensaver clock. The starfield is always there; the streamed NASA video
 * replaces it only while it is selected and its first frame has been rendered (not while loading, not after
 * the stream failed or timed out).
 */
enum ScreensaverKind {
    STARFIELD, VIDEO;

    static ScreensaverKind choose(LauncherSettings.ScreensaverType type, VideoPlayback.Phase phase) {
        return type == LauncherSettings.ScreensaverType.NASA_VIDEO && phase == VideoPlayback.Phase.RENDERING
                ? VIDEO : STARFIELD;
    }
}
