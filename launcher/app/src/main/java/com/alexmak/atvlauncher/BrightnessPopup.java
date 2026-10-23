package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;

/** Frosted popup with ten tall dashes showing the lamp brightness (the owner routes the D-pad keys to it). */
public final class BrightnessPopup extends View {
    private static final int DASHES = LampState.MAX_LEVEL;
    private static final float DASH_W_DP = 8;
    private static final float DASH_H_DP = 36;
    private static final float GAP_DP = 8;
    private static final float PAD_DP = 18;
    private static final float TEXT_SP = 16;
    private static final float TEXT_GAP_DP = 12;
    private static final float POPUP_GAP_DP = 10;
    private static final float MARGIN_DP = 16;
    private static final int DIM_ALPHA = 56;
    private static final int LOADING_ALPHA = 28;

    private final float density;
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dash = new RectF();
    private final GradientDrawable bg = new GradientDrawable();
    private final int fillColor;
    private final int strokeColor;
    private final int accentColor;
    private LampState state = LampState.loading();
    private boolean adjusting;

    public BrightnessPopup(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        fillColor = context.getColor(R.color.menu_fill);
        strokeColor = context.getColor(R.color.glass_stroke);
        accentColor = context.getColor(R.color.accent_glow);
        bg.setCornerRadius(18 * density);
        applyBackground();
        setBackground(bg);
        textPaint.setColor(context.getColor(R.color.text_primary));
        textPaint.setTextSize(TEXT_SP * density);
        textPaint.setTypeface(getResources().getFont(R.font.outfit_regular));
        textPaint.setTextAlign(Paint.Align.CENTER);
        setVisibility(GONE);
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    void setState(LampState s) {
        state = s;
        requestLayout();
        invalidate();
    }

    void setAdjusting(boolean on) {
        adjusting = on;
        applyBackground();
        invalidate();
    }

    /** Shows the popup below the anchor (screen coordinates), or above it when there is no room below. */
    void show(RectF anchor) {
        setVisibility(VISIBLE);
        measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float w = getMeasuredWidth();
        float h = getMeasuredHeight();
        float screenW = getResources().getDisplayMetrics().widthPixels;
        float screenH = getResources().getDisplayMetrics().heightPixels;
        float margin = MARGIN_DP * density;
        float gap = POPUP_GAP_DP * density;
        float x = Math.max(margin, Math.min(screenW - w - margin, anchor.centerX() - w / 2));
        float y = anchor.bottom + gap;
        if (y + h > screenH - margin) y = anchor.top - h - gap;
        setX(x);
        setY(y);
    }

    void hide() {
        setVisibility(GONE);
    }

    private void applyBackground() {
        bg.setColor(fillColor);
        if (adjusting) bg.setStroke(Math.round(2 * density), accentColor);
        else bg.setStroke(1, strokeColor);
    }

    private float dashesWidth() {
        return DASHES * DASH_W_DP * density + (DASHES - 1) * GAP_DP * density;
    }

    private boolean hasText() {
        return state.kind == LampState.Kind.ERROR;
    }

    private float textHeight() {
        return hasText() ? TEXT_GAP_DP * density + textPaint.getFontSpacing() : 0;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float pad = PAD_DP * density;
        float w = dashesWidth();
        if (hasText()) w = Math.max(w, textPaint.measureText(state.message));
        setMeasuredDimension(Math.round(w + 2 * pad),
                Math.round(DASH_H_DP * density + textHeight() + 2 * pad));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = PAD_DP * density;
        float dw = DASH_W_DP * density;
        float step = dw + GAP_DP * density;
        float left = (getWidth() - dashesWidth()) / 2;
        int lit = state.kind == LampState.Kind.READY ? state.level : 0;
        int dimAlpha = state.kind == LampState.Kind.LOADING ? LOADING_ALPHA : DIM_ALPHA;
        for (int i = 0; i < DASHES; i++) {
            dashPaint.setColor(0xFFFFFFFF);
            dashPaint.setAlpha(i < lit ? 255 : dimAlpha);
            float x = left + i * step;
            dash.set(x, pad, x + dw, pad + DASH_H_DP * density);
            canvas.drawRoundRect(dash, dw / 2, dw / 2, dashPaint);
        }
        if (hasText()) {
            float baseline = pad + DASH_H_DP * density + TEXT_GAP_DP * density - textPaint.ascent();
            canvas.drawText(state.message, getWidth() / 2f, baseline, textPaint);
        }
    }
}
