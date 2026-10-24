package com.johnmak.atvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The single translucent highlight. Covers the whole page, draws one rounded rectangle and glides it towards the
 * latest target ({@link Approach}: decelerating, no bounce). A new target while moving only changes where it is
 * heading, the motion continues from the current rectangle. Frames run only while moving; nothing is allocated per move.
 */
public final class GlassView extends View {
    private static final long MAX_FRAME_MS = 64;
    private static final double EPS_PX = 0.5;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF current = new RectF();
    private final RectF to = new RectF();
    /** Accent halo rings, built once; {@code null} = disabled (the default for every screen except Home). */
    private Paint[] glow;
    private float[] glowInsetPx;
    private final RectF glowRect = new RectF();
    private float radiusCurrent;
    private float radiusTo;
    private boolean placed;
    private boolean running;
    private long lastFrameMs;
    private final Runnable frame = this::onFrame;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(context.getColor(R.color.glass_fill));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        stroke.setColor(context.getColor(R.color.glass_stroke));
        setFocusable(false);
        setClickable(false);
    }

    /**
     * Turns on a subtle accent halo behind the highlight (Home only). Opt-in: callers that never invoke this keep the
     * plain white glass. Paints are built once here; nothing is allocated per frame.
     */
    void enableAccentGlow(int color) {
        float d = getResources().getDisplayMetrics().density;
        float[] insets = {2f * d, 6f * d, 11f * d};
        float[] widths = {3f * d, 4f * d, 5f * d};
        int[] alphas = {90, 46, 22};
        Paint[] rings = new Paint[insets.length];
        for (int i = 0; i < rings.length; i++) {
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setStyle(Paint.Style.STROKE);
            g.setStrokeWidth(widths[i]);
            g.setColor((alphas[i] << 24) | (color & 0x00FFFFFF));
            rings[i] = g;
        }
        glowInsetPx = insets;
        glow = rings;
        invalidate();
    }

    void moveTo(RectF target, float radius, boolean animate) {
        if (placed && radius == radiusTo && target.equals(to)) return;
        to.set(target);
        radiusTo = radius;
        if (!placed || !animate) {
            placed = true;
            stop();
            current.set(target);
            radiusCurrent = radius;
            invalidate();
            return;
        }
        if (!running) {
            running = true;
            lastFrameMs = 0;
            postOnAnimation(frame);
        }
    }

    private void stop() {
        removeCallbacks(frame);
        running = false;
    }

    private void onFrame() {
        long now = AnimationUtils.currentAnimationTimeMillis();
        long dt = lastFrameMs == 0 ? 16 : Math.max(1, Math.min(MAX_FRAME_MS, now - lastFrameMs));
        lastFrameMs = now;
        current.set(
                (float) Approach.step(current.left, to.left, dt, EPS_PX),
                (float) Approach.step(current.top, to.top, dt, EPS_PX),
                (float) Approach.step(current.right, to.right, dt, EPS_PX),
                (float) Approach.step(current.bottom, to.bottom, dt, EPS_PX));
        radiusCurrent = (float) Approach.step(radiusCurrent, radiusTo, dt, EPS_PX);
        invalidate();
        if (current.equals(to) && radiusCurrent == radiusTo) {
            running = false;
        } else {
            postOnAnimation(frame);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!placed) return;
        if (glow != null) {
            for (int i = glow.length - 1; i >= 0; i--) {
                glowRect.set(current);
                glowRect.inset(-glowInsetPx[i], -glowInsetPx[i]);
                float r = radiusCurrent + glowInsetPx[i];
                canvas.drawRoundRect(glowRect, r, r, glow[i]);
            }
        }
        canvas.drawRoundRect(current, radiusCurrent, radiusCurrent, fill);
        canvas.drawRoundRect(current, radiusCurrent, radiusCurrent, stroke);
    }
}
