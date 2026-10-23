package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Top-bar item without any background, built from a status dot, an icon and a text label as requested. Focus is drawn by the shared glass view. */
final class TopBarItem extends LinearLayout {
    private final TextView label;
    private final ImageView icon;
    private final View dot;
    private final GradientDrawable dotShape = new GradientDrawable();

    TopBarItem(Context context, boolean withDot, boolean withIcon, boolean withLabel) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        if (withDot) {
            dot = new View(context);
            dotShape.setShape(GradientDrawable.OVAL);
            dot.setBackground(dotShape);
            LayoutParams lp = new LayoutParams(Math.round(8 * d), Math.round(8 * d));
            lp.setMarginEnd(Math.round(8 * d));
            addView(dot, lp);
        } else {
            dot = null;
        }

        if (withIcon) {
            icon = new ImageView(context);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams lp = new LayoutParams(Math.round(26 * d), Math.round(26 * d));
            if (withLabel) lp.setMarginEnd(Math.round(8 * d));
            addView(icon, lp);
        } else {
            icon = null;
        }

        if (withLabel) {
            label = createLabel(context);
            addView(label);
        } else {
            label = null;
        }
    }

    /** The top-bar label typography: Outfit Regular 15sp, white, one line. */
    static TextView createLabel(Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        TextView label = new TextView(context);
        label.setTextSize(15);
        label.setTypeface(context.getResources().getFont(R.font.outfit_regular));
        label.setTextColor(context.getColor(R.color.text_primary));
        label.setIncludeFontPadding(false);
        label.setSingleLine(true);
        label.setMaxWidth(Math.round(220 * d));
        return label;
    }

    void setIcon(int drawableRes) {
        icon.setImageResource(drawableRes);
    }

    void setLabel(String text) {
        label.setText(text);
    }

    void setDotColor(int color) {
        dotShape.setColor(color);
    }

    View dot() {
        return dot;
    }
}
