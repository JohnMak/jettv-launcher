package com.johnmak.atvlauncher;

/** Pure timing and geometry of the video screensaver. */
final class VideoPlayback {
    static final long CROSSFADE_MS = 1500;
    /** The next clip starts streaming (prepareAsync) this long before the crossfade starts. */
    static final long PREPARE_LEAD_MS = 5000;
    /** No first rendered frame within this time after the start = no network / host blocked: stay on the starfield. */
    static final long STARTUP_TIMEOUT_MS = 15_000;
    /** Brightness cap of the video, as a black overlay of alpha 1 - this (OLED). */
    static final float MAX_BRIGHTNESS = 0.85f;

    /** Streaming state of one screensaver session. GAVE_UP is final until the next session. */
    enum Phase { LOADING, RENDERING, GAVE_UP }

    enum Event { RENDERING_STARTED, FAILED }

    private VideoPlayback() {}

    static Phase nextPhase(Phase current, Event event) {
        if (event == Event.FAILED || current == Phase.GAVE_UP) return Phase.GAVE_UP;
        return Phase.RENDERING;
    }

    static int nextIndex(int index, int count) {
        if (count <= 0) throw new IllegalArgumentException("count");
        return (index + 1) % count;
    }

    /** Playback position at which the crossfade into the next clip starts. */
    static long crossfadeStartMs(long durationMs) {
        return Math.max(0, durationMs - CROSSFADE_MS);
    }

    /** Position at which the next clip starts being prepared. */
    static long prepareStartMs(long durationMs) {
        return Math.max(0, crossfadeStartMs(durationMs) - PREPARE_LEAD_MS);
    }

    /**
     * View scale (x, y) that makes a video stretched to the whole TextureView look centre-cropped:
     * the video is scaled uniformly until it covers the view, the overflow is cut off.
     */
    static float[] centerCropScale(float viewW, float viewH, float videoW, float videoH) {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) throw new IllegalArgumentException("size");
        float viewAspect = viewW / viewH;
        float videoAspect = videoW / videoH;
        return videoAspect > viewAspect
                ? new float[]{videoAspect / viewAspect, 1f}
                : new float[]{1f, viewAspect / videoAspect};
    }
}
