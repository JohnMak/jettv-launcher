package com.johnmak.atvlauncher;

/**
 * What is actually visible behind the screensaver clock. The starfield is always there; the streamed NASA video or the
 * ART paintings layer replaces it only while that type is selected and it has something to show (not while loading, not
 * after the stream failed/timed out, not when the art pool is exhausted/empty/unreachable).
 */
enum ScreensaverKind {
    STARFIELD, VIDEO, ART;

    static ScreensaverKind choose(LauncherSettings.ScreensaverType type, VideoPlayback.Phase phase) {
        return type == LauncherSettings.ScreensaverType.NASA_VIDEO && phase == VideoPlayback.Phase.RENDERING
                ? VIDEO : STARFIELD;
    }

    static ScreensaverKind choose(LauncherSettings.ScreensaverType type, ArtPlayback.Phase phase) {
        return type == LauncherSettings.ScreensaverType.ART && phase == ArtPlayback.Phase.RENDERING
                ? ART : STARFIELD;
    }
}
