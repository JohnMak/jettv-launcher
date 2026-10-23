package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class VideoPlaybackTest {
    @Test public void playlistWraps() {
        assertEquals(1, VideoPlayback.nextIndex(0, 3));
        assertEquals(0, VideoPlayback.nextIndex(2, 3));
        assertEquals(0, VideoPlayback.nextIndex(0, 1));
    }

    @Test public void crossfadeStartsBeforeTheEnd() {
        assertEquals(38_500, VideoPlayback.crossfadeStartMs(40_000));
        assertEquals(33_500, VideoPlayback.prepareStartMs(40_000));
        assertEquals(0, VideoPlayback.crossfadeStartMs(1_000));
        assertEquals(0, VideoPlayback.prepareStartMs(5_000));
    }

    @Test public void phaseMovesToRenderingOnlyFromLoadingAndGivingUpIsFinal() {
        VideoPlayback.Phase loading = VideoPlayback.Phase.LOADING;
        assertEquals(VideoPlayback.Phase.RENDERING, VideoPlayback.nextPhase(loading, VideoPlayback.Event.RENDERING_STARTED));
        assertEquals(VideoPlayback.Phase.GAVE_UP, VideoPlayback.nextPhase(loading, VideoPlayback.Event.FAILED));
        assertEquals(VideoPlayback.Phase.GAVE_UP, VideoPlayback.nextPhase(VideoPlayback.Phase.RENDERING, VideoPlayback.Event.FAILED));
        // a late first frame after the timeout does not bring the video back
        assertEquals(VideoPlayback.Phase.GAVE_UP,
                VideoPlayback.nextPhase(VideoPlayback.Phase.GAVE_UP, VideoPlayback.Event.RENDERING_STARTED));
        assertEquals(VideoPlayback.Phase.RENDERING,
                VideoPlayback.nextPhase(VideoPlayback.Phase.RENDERING, VideoPlayback.Event.RENDERING_STARTED));
    }

    @Test public void startupTimeoutIsFifteenSeconds() {
        assertEquals(15_000, VideoPlayback.STARTUP_TIMEOUT_MS);
    }

    @Test public void videoIsShownOnlyWhenSelectedAndRendering() {
        LauncherSettings.ScreensaverType video = LauncherSettings.ScreensaverType.NASA_VIDEO;
        assertEquals(ScreensaverKind.VIDEO, ScreensaverKind.choose(video, VideoPlayback.Phase.RENDERING));
        assertEquals(ScreensaverKind.STARFIELD, ScreensaverKind.choose(video, VideoPlayback.Phase.LOADING));
        assertEquals(ScreensaverKind.STARFIELD, ScreensaverKind.choose(video, VideoPlayback.Phase.GAVE_UP));
        assertEquals(ScreensaverKind.STARFIELD,
                ScreensaverKind.choose(LauncherSettings.ScreensaverType.STARFIELD, VideoPlayback.Phase.RENDERING));
    }

    @Test public void centerCropScalesTheOverflowingAxis() {
        assertArrayEquals(new float[]{1f, 1f}, VideoPlayback.centerCropScale(1920, 1080, 1920, 1080), 1e-6f);
        // wider video on a 16:9 view: x grows
        assertArrayEquals(new float[]{(21f / 9f) / (16f / 9f), 1f}, VideoPlayback.centerCropScale(1920, 1080, 2100, 900), 1e-5f);
        // taller video: y grows
        assertArrayEquals(new float[]{1f, (16f / 9f) / (4f / 3f)}, VideoPlayback.centerCropScale(1920, 1080, 1600, 1200), 1e-5f);
    }
}
