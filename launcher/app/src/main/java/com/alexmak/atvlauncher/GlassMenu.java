package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Small frosted popup menu with a virtual selection (the owner routes the D-pad keys to it). */
public final class GlassMenu extends LinearLayout {
    private final float density;
    private final List<TextView> entries = new ArrayList<>();
    private int selected;

    public GlassMenu(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int pad = Math.round(8 * density);
        setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(context.getColor(R.color.menu_fill));
        bg.setCornerRadius(18 * density);
        bg.setStroke(1, context.getColor(R.color.glass_stroke));
        setBackground(bg);
        setVisibility(GONE);
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    int selected() {
        return selected;
    }

    /** Shows the menu below the anchor (screen coordinates), or above it when there is no room below. */
    void show(String[] labels, RectF anchor) {
        removeAllViews();
        entries.clear();
        for (String label : labels) {
            TextView t = new TextView(getContext());
            t.setText(label);
            t.setTextSize(18);
            t.setTypeface(getResources().getFont(R.font.outfit_regular));
            t.setTextColor(getContext().getColor(R.color.text_primary));
            t.setIncludeFontPadding(false);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setMinWidth(Math.round(240 * density));
            t.setPadding(Math.round(18 * density), Math.round(12 * density),
                    Math.round(18 * density), Math.round(12 * density));
            addView(t);
            entries.add(t);
        }
        select(0);
        setVisibility(VISIBLE);
        measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float w = getMeasuredWidth();
        float h = getMeasuredHeight();
        float screenW = getResources().getDisplayMetrics().widthPixels;
        float screenH = getResources().getDisplayMetrics().heightPixels;
        float margin = 16 * density;
        float gap = 10 * density;
        float x = Math.max(margin, Math.min(screenW - w - margin, anchor.centerX() - w / 2));
        float y = anchor.bottom + gap;
        if (y + h > screenH - margin) y = anchor.top - h - gap;
        setX(x);
        setY(y);
    }

    void hide() {
        setVisibility(GONE);
    }

    void moveSelection(int delta) {
        select(Math.max(0, Math.min(entries.size() - 1, selected + delta)));
    }

    private void select(int index) {
        selected = index;
        for (int i = 0; i < entries.size(); i++) {
            TextView t = entries.get(i);
            if (i == index) {
                GradientDrawable hi = new GradientDrawable();
                hi.setColor(getContext().getColor(R.color.glass_fill));
                hi.setCornerRadius(12 * density);
                t.setBackground(hi);
            } else {
                t.setBackground(null);
            }
        }
    }
}
