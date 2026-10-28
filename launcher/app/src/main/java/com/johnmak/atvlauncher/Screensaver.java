package com.johnmak.atvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * The full-screen screensaver: a background (the starfield, with the streamed NASA video crossfaded over it, per {@link ScreensaverKind};
 * rebuilt every time {@link #prepare} is called) under the one shared clock/date/weather {@link ScreensaverOverlay}.
 * Used by the in-launcher idle stage ({@link IdleController}) and by the system dream ({@link StarfieldDream}).
 */
final class Screensaver extends FlatFrameLayout {
    private final ScreensaverOverlay overlay;
    private View starfield;
    private VideoScreensaverView video;
    private ArtScreensaverView art;
    /** The ART subsystem's SHARED resources (finding 3): created once, on first ART use, and REUSED across every
     * {@link #prepare} (view replacement). Owning them here — at the screensaver's lifetime, not the per-activation
     * view's — bounds background threads and live bitmaps GLOBALLY across all activations. */
    private ExecutorService artPool;
    private Semaphore artBudget;
    private LauncherSettings.ScreensaverType type = LauncherSettings.ScreensaverType.STARFIELD;

    Screensaver(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        overlay = new ScreensaverOverlay(context);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /**
     * Replaces the background according to the settings: the starfield always, and over it — for the NASA type a
     * streaming video layer (transparent until its first frame), for the ART type a paintings slideshow (transparent
     * until its first ready image, hidden again when the pool is exhausted/empty/unreachable) — see {@link ScreensaverKind}.
     */
    void prepare(LauncherSettings settings) {
        if (starfield != null) removeView(starfield);
        if (video != null) removeView(video);
        if (art != null) {
            art.teardown(); // explicit art-layer teardown (cancel fetch/decode, release bitmaps) before removal
            removeView(art);
            art = null;
        }
        type = settings.screensaverType;
        overlay.setAttributionVisible(type == LauncherSettings.ScreensaverType.ART);
        starfield = new StarfieldView(getContext());
        addView(starfield, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        video = null;
        if (type == LauncherSettings.ScreensaverType.NASA_VIDEO) {
            VideoScreensaverView layer = new VideoScreensaverView(getContext(),
                    Arrays.asList(getResources().getStringArray(R.array.nasa_videos)), this::onVideoPhase);
            layer.setAlpha(0f);
            video = layer;
            addView(layer, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else if (type == LauncherSettings.ScreensaverType.ART) {
            if (artPool == null) { // one shared owner, created lazily and reused across all activations (finding 3)
                artPool = ArtScreensaverView.newSharedPool();
                artBudget = ArtScreensaverView.newSharedBudget();
            }
            // The listener captures THIS layer; a callback from a layer later replaced by prepare() is a no-op
            // (generation-checked by identity against the current `art` field).
            ArtScreensaverView[] self = new ArtScreensaverView[1];
            self[0] = new ArtScreensaverView(getContext(), settings.artPreset.key, artPool, artBudget,
                    phase -> onArtPhase(self[0], phase));
            self[0].setAlpha(0f);
            art = self[0];
            addView(self[0], 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    private void onVideoPhase(VideoPlayback.Phase phase) {
        float target = ScreensaverKind.choose(type, phase) == ScreensaverKind.VIDEO ? 1f : 0f;
        video.animate().alpha(target).setDuration(VideoPlayback.CROSSFADE_MS).start();
    }

    private void onArtPhase(ArtScreensaverView layer, ArtPlayback.Phase phase) {
        if (layer != art) return; // a phase from a replaced layer must not touch the current one
        float target = ScreensaverKind.choose(type, phase) == ScreensaverKind.ART ? 1f : 0f;
        art.animate().alpha(target).setDuration(ArtPlayback.CROSSFADE_MS).start();
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
