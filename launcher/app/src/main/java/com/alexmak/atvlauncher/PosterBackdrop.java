package com.alexmak.atvlauncher;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;

/**
 * Full-screen blurred poster behind the page: crossfades between posters and fades out when hidden, slowly, ease-in-out.
 * It is static; the view gets a hardware layer only while a fade runs.
 */
final class PosterBackdrop {
    private static final long FADE_MS = 600;

    private final Interpolator ease = new AccelerateDecelerateInterpolator();
    private final ImageView view;
    private BitmapDrawable shown;
    private ValueAnimator crossfade;

    PosterBackdrop(ImageView view) {
        this.view = view;
        view.setAlpha(0f);
    }

    /** Shows a prepared backdrop bitmap (see {@link BackdropBlur}). */
    void show(Bitmap bitmap) {
        BitmapDrawable next = new BitmapDrawable(view.getResources(), bitmap);
        next.setFilterBitmap(true);
        next.setDither(true);
        if (crossfade != null) crossfade.cancel();
        if (shown == null || view.getAlpha() == 0f) {
            view.setImageDrawable(next);
        } else {
            shown.setAlpha(255);
            next.setAlpha(0);
            view.setImageDrawable(new LayerDrawable(new Drawable[]{shown, next}));
            crossfade = ValueAnimator.ofInt(0, 255).setDuration(FADE_MS);
            crossfade.setInterpolator(ease);
            crossfade.addUpdateListener(a -> next.setAlpha((int) a.getAnimatedValue()));
            crossfade.start();
        }
        shown = next;
        fadeTo(1f);
    }

    void hide() {
        fadeTo(0f);
    }

    /** Drops the shown bitmap at once (memory trim); the next {@link #show} fades in from nothing. */
    void clear() {
        if (crossfade != null) crossfade.cancel();
        view.animate().cancel();
        view.setAlpha(0f);
        view.setImageDrawable(null);
        shown = null;
    }

    private void fadeTo(float alpha) {
        view.animate().alpha(alpha).setDuration(FADE_MS).setInterpolator(ease).withLayer().start();
    }
}
