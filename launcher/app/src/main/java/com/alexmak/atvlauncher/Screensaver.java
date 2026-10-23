package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.Arrays;
import java.util.TimeZone;

/**
 * The full-screen screensaver: a background (the starfield, with the streamed NASA video crossfaded over it, per {@link ScreensaverKind};
 * rebuilt every time {@link #prepare} is called) under the one shared clock/date/weather {@link ScreensaverOverlay}.
 * Used by the in-launcher idle stage ({@link IdleController}) and by the system dream ({@link StarfieldDream}).
 */
final class Screensaver extends FlatFrameLayout {
    private final ScreensaverOverlay overlay;
    private View starfield;
    private VideoScreensaverView video;
    private LauncherSettings.ScreensaverType type = LauncherSettings.ScreensaverType.STARFIELD;

    Screensaver(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        overlay = new ScreensaverOverlay(context);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /**
     * Replaces the background according to the settings: the starfield always, and for the NASA type a streaming
     * video layer over it (transparent until its first frame, see {@link ScreensaverKind}).
     */
    void prepare(LauncherSettings settings) {
        if (starfield != null) removeView(starfield);
        if (video != null) removeView(video);
        type = settings.screensaverType;
        starfield = new StarfieldView(getContext());
        addView(starfield, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        video = null;
        if (type == LauncherSettings.ScreensaverType.NASA_VIDEO) {
            VideoScreensaverView layer = new VideoScreensaverView(getContext(),
                    Arrays.asList(getResources().getStringArray(R.array.nasa_videos)), this::onVideoPhase);
            layer.setAlpha(0f);
            video = layer;
            addView(layer, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    private void onVideoPhase(VideoPlayback.Phase phase) {
        float target = ScreensaverKind.choose(type, phase) == ScreensaverKind.VIDEO ? 1f : 0f;
        video.animate().alpha(target).setDuration(VideoPlayback.CROSSFADE_MS).start();
    }

    void setTimeZone(TimeZone zone) {
        overlay.setTimeZone(zone);
    }

    void setWeatherText(CharSequence text) {
        overlay.setWeatherText(text);
    }

    void setWeatherIcon(Drawable icon) {
        overlay.setWeatherIcon(icon);
    }
}
