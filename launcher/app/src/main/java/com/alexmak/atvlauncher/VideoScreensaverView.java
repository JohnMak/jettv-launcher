package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

import java.util.List;

/**
 * Screensaver video layer that streams the clips (progressive HTTP, nothing stored) as an endless playlist, muted,
 * centre-cropped, capped at {@link VideoPlayback#MAX_BRIGHTNESS} by a black overlay (OLED). It is transparent and
 * lies over the starfield; it reports its {@link VideoPlayback.Phase} so the owner can crossfade to it once the
 * first frame is rendered. The playback itself is {@link PlaylistVideoView}. Plays only while attached and shown;
 * the players are released as soon as it is not.
 */
final class VideoScreensaverView extends FrameLayout {
    interface PhaseListener {
        void onPhase(VideoPlayback.Phase phase);
    }

    private final List<String> urls;
    private final PlaylistVideoView video;
    private boolean running;

    VideoScreensaverView(Context context, List<String> urls, PhaseListener listener) {
        super(context);
        if (urls.isEmpty()) throw new IllegalArgumentException("no clips");
        this.urls = urls;
        video = new PlaylistVideoView(context, url -> url, true, listener::onPhase);
        LayoutParams full = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(video, full);
        View dim = new View(context);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(1f - VideoPlayback.MAX_BRIGHTNESS);
        addView(dim, full);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateRunning();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        updateRunning();
    }

    private void updateRunning() {
        boolean shouldRun = isAttachedToWindow() && isShown();
        if (shouldRun == running) return;
        running = shouldRun;
        if (running) video.play(urls);
        else video.stop();
    }
}
