package com.johnmak.atvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Small frosted popup menu with a virtual selection (the owner routes the D-pad keys to it). Besides plain text
 * entries it shows a checklist (icon, label, checkbox) of which at most {@link ChecklistWindow#VISIBLE} lines are
 * visible; the selection scrolls the window.
 */
public final class GlassMenu extends LinearLayout {
    private final float density;
    private final List<View> entries = new ArrayList<>();
    private final List<TextView> boxes = new ArrayList<>();
    private final List<Boolean> checked = new ArrayList<>();
    private ChecklistWindow window;
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

    /** One line of a checklist. */
    static final class Check {
        final Bitmap icon;
        final String label;
        final boolean checked;

        Check(Bitmap icon, String label, boolean checked) {
            this.icon = icon;
            this.label = label;
            this.checked = checked;
        }
    }

    /** Shows a checklist with a title, centred on the screen. */
    void showChecklist(String title, List<Check> items) {
        removeAllViews();
        entries.clear();
        boxes.clear();
        checked.clear();
        window = new ChecklistWindow(items.size());
        TextView head = new TextView(getContext());
        head.setText(title);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.setTypeface(getResources().getFont(R.font.outfit_light));
        head.setTextColor(getContext().getColor(R.color.text_secondary));
        head.setIncludeFontPadding(false);
        head.setPadding(dp(18), dp(8), dp(18), dp(8));
        addView(head);
        for (Check item : items) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(14), 0, dp(14), 0);
            ImageView icon = new ImageView(getContext());
            icon.setImageBitmap(item.icon);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(32), dp(32));
            iconLp.setMarginEnd(dp(14));
            line.addView(icon, iconLp);
            TextView name = new TextView(getContext());
            name.setText(item.label);
            name.setTextColor(getContext().getColor(R.color.text_primary));
            name.setTypeface(getResources().getFont(R.font.outfit_regular));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            name.setIncludeFontPadding(false);
            name.setSingleLine(true);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView box = new TextView(getContext());
            box.setGravity(Gravity.CENTER);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            box.setIncludeFontPadding(false);
            box.setTextColor(0xFF000000);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            boxLp.setMarginStart(dp(14));
            line.addView(box, boxLp);
            addView(line, new LinearLayout.LayoutParams(dp(420), dp(48)));
            entries.add(line);
            boxes.add(box);
            checked.add(item.checked);
            renderBox(entries.size() - 1);
        }
        select(0);
        showWindow();
        setVisibility(VISIBLE);
        measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        setX((getResources().getDisplayMetrics().widthPixels - getMeasuredWidth()) / 2f);
        setY((getResources().getDisplayMetrics().heightPixels - getMeasuredHeight()) / 2f);
    }

    /** Sets the checkbox of line {@code index}. */
    void setChecked(int index, boolean on) {
        checked.set(index, on);
        renderBox(index);
    }

    private void showWindow() {
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setVisibility(window.isVisible(i) ? VISIBLE : GONE);
        }
    }

    /** Filled square with a tick when on, an empty outline when off. */
    private void renderBox(int index) {
        boolean on = checked.get(index);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        if (on) {
            bg.setColor(getContext().getColor(R.color.text_primary));
        } else {
            bg.setStroke(dp(2), getContext().getColor(R.color.text_secondary));
        }
        TextView box = boxes.get(index);
        box.setBackground(bg);
        box.setText(on ? "✓" : "");
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    /** Shows the menu below the anchor (screen coordinates), or above it when there is no room below. */
    void show(String[] labels, RectF anchor) {
        removeAllViews();
        entries.clear();
        boxes.clear();
        checked.clear();
        window = null;
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
        if (window != null) {
            if (!window.move(delta)) return;
            select(window.selected());
            showWindow();
            return;
        }
        select(Math.max(0, Math.min(entries.size() - 1, selected + delta)));
    }

    private void select(int index) {
        selected = index;
        for (int i = 0; i < entries.size(); i++) {
            View t = entries.get(i);
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
