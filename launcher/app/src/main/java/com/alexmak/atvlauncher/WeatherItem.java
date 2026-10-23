package com.alexmak.atvlauncher;

import android.content.Context;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Home weather block under the date: icon + temperature (+ phenomenon word), and a dimmer second line: city, wide gap, day max / night min. Focus is drawn by the shared glass view. */
final class WeatherItem extends LinearLayout {
    private final ImageView icon;
    private final TextView primary;
    private final TextView city;
    private final TextView range;
    /** Wide gap between the city and the day / night range. */
    private final int rangeGap;

    WeatherItem(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageView(context);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams ilp = new LayoutParams(Math.round(22 * d), Math.round(22 * d));
        ilp.setMarginEnd(Math.round(8 * d));
        row.addView(icon, ilp);
        primary = text(context, R.font.outfit_regular, 20, R.color.text_primary);
        row.addView(primary);
        addView(row);

        LinearLayout second = new LinearLayout(context);
        city = text(context, R.font.outfit_light, 14, R.color.text_secondary);
        range = text(context, R.font.outfit_light, 14, R.color.text_secondary);
        second.addView(city);
        second.addView(range);
        rangeGap = Math.round(17 * d);
        LayoutParams slp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = Math.round(3 * d);
        addView(second, slp);
    }

    private static TextView text(Context context, int font, int sp, int color) {
        TextView t = new TextView(context);
        t.setTypeface(context.getResources().getFont(font));
        t.setTextSize(sp);
        t.setTextColor(context.getColor(color));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    void render(WeatherDisplay display) {
        icon.setImageResource(WeatherIcons.drawable(display.icon));
        primary.setText(display.primary);
        city.setText(display.city);
        city.setVisibility(display.city.isEmpty() ? GONE : VISIBLE);
        ((LayoutParams) range.getLayoutParams()).setMarginStart(display.city.isEmpty() ? 0 : rangeGap);
        range.setText(display.range);
        range.setVisibility(display.range.isEmpty() ? GONE : VISIBLE);
    }
}
