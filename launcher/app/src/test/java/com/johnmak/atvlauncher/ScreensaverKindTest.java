package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScreensaverKindTest {
    private static final LauncherSettings.ScreensaverType ART = LauncherSettings.ScreensaverType.ART;
    private static final LauncherSettings.ScreensaverType STARFIELD = LauncherSettings.ScreensaverType.STARFIELD;
    private static final LauncherSettings.ScreensaverType NASA = LauncherSettings.ScreensaverType.NASA_VIDEO;

    @Test public void artIsVisibleOnlyWhileRendering() {
        assertEquals(ScreensaverKind.ART, ScreensaverKind.choose(ART, ArtPlayback.Phase.RENDERING));
        assertEquals(ScreensaverKind.STARFIELD, ScreensaverKind.choose(ART, ArtPlayback.Phase.LOADING));
        assertEquals(ScreensaverKind.STARFIELD, ScreensaverKind.choose(ART, ArtPlayback.Phase.EXHAUSTED));
    }

    @Test public void nonArtTypesNeverPickArt() {
        assertEquals(ScreensaverKind.STARFIELD, ScreensaverKind.choose(STARFIELD, ArtPlayback.Phase.RENDERING));
        assertEquals(ScreensaverKind.STARFIELD, ScreensaverKind.choose(NASA, ArtPlayback.Phase.RENDERING));
    }

    @Test public void theVideoOverloadIsUnchanged() {
        assertEquals(ScreensaverKind.VIDEO, ScreensaverKind.choose(NASA, VideoPlayback.Phase.RENDERING));
        assertEquals(ScreensaverKind.STARFIELD, ScreensaverKind.choose(ART, VideoPlayback.Phase.RENDERING));
    }
}
