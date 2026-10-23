package com.alexmak.atvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Picture whose brightness follows {@link FocusDim}: a dark colour filter, so it never turns see-through.
 * One reusable animator per view; filters are shared and cached per brightness step, so a frame allocates nothing.
 */
public final class DimImageView extends ImageView {
    private static final int STEPS = 50;
    private static final ColorFilter[] FILTERS = new ColorFilter[STEPS];

    private float brightness = FocusDim.level(FocusDim.Role.TILE, false);
    private int filterStep = -1;
    private float animFrom;
    private float animTo;
    private ValueAnimator animator;

    public DimImageView(Context context) {
        this(context, null);
    }

    public DimImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        applyFilter();
    }

    void setFocused(boolean focused, boolean animate) {
        float target = FocusDim.level(FocusDim.Role.TILE, focused);
        boolean running = animator != null && animator.isRunning();
        if (!animate) {
            if (running) animator.cancel();
            setBrightness(target);
            return;
        }
        if (running ? animTo == target : brightness == target) return;
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(FocusDim.DURATION_MS);
            animator.addUpdateListener(a -> setBrightness(animFrom + (animTo - animFrom) * a.getAnimatedFraction()));
        } else if (running) {
            animator.cancel();
        }
        animFrom = brightness;
        animTo = target;
        animator.start();
    }

    private void setBrightness(float value) {
        brightness = value;
        applyFilter();
    }

    private void applyFilter() {
        int step = brightness >= 1f ? STEPS : Math.round(brightness * STEPS);
        if (step == filterStep) return;
        filterStep = step;
        if (step >= STEPS) {
            setColorFilter(null);
            return;
        }
        ColorFilter filter = FILTERS[step];
        if (filter == null) {
            int c = Math.round(step * 255f / STEPS);
            filter = new LightingColorFilter(0xFF000000 | (c << 16) | (c << 8) | c, 0);
            FILTERS[step] = filter;
        }
        setColorFilter(filter);
    }
}
