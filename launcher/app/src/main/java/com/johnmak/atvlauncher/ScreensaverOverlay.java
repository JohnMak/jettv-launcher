package com.johnmak.atvlauncher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The clock/date/weather block shared by every screensaver: white at 15 % opacity, centred, creeping around the
 * centre every {@link OverlayDrift#INTERVAL_MS} (OLED burn-in). Ticks only while attached and shown.
 * Weather is fed via {@link #setWeatherText} / {@link #setWeatherIcon}.
 */
final class ScreensaverOverlay extends FrameLayout {
    private final OverlayDrift drift = new OverlayDrift(System.nanoTime() ^ 0x5DEECE66DL);
    private final LinearLayout block;
    private final TextView clock;
    private final TextView date;
    private final LinearLayout weatherRow;
    private final ImageView weatherIcon;
    private final TextView weatherText;
    private final SimpleDateFormat timeFormat;
    private final SimpleDateFormat dateFormat;

    private TimeZone zone = TimeZone.getDefault();
    private boolean running;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            updateClock();
            postDelayed(this, MinuteTick.delayMillis(System.currentTimeMillis(), zone) + 50L);
        }
    };

    private final Runnable driftTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            drift.next();
            block.animate()
                    .translationX(drift.x() * getWidth())
                    .translationY(drift.y() * getHeight())
                    .setInterpolator(new LinearInterpolator())
                    .setDuration(OverlayDrift.INTERVAL_MS / 2)
                    .start();
            postDelayed(this, OverlayDrift.INTERVAL_MS);
        }
    };

    ScreensaverOverlay(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.view_screensaver_overlay, this, true);
        block = findViewById(R.id.overlay_block);
        clock = findViewById(R.id.overlay_clock);
        date = findViewById(R.id.overlay_date);
        weatherRow = findViewById(R.id.overlay_weather);
        weatherIcon = findViewById(R.id.overlay_weather_icon);
        weatherText = findViewById(R.id.overlay_weather_text);
        Locale locale = "en".equals(context.getString(R.string.date_language)) ? Locale.ENGLISH : new Locale("ru");
        timeFormat = new SimpleDateFormat("HH:mm", locale);
        dateFormat = new SimpleDateFormat("EEEE, d MMMM", locale);
        updateClock();
    }

    /** Zone of the clock and date (the city's timezone, or the device's); restarts the minute tick. */
    void setTimeZone(TimeZone zone) {
        this.zone = zone;
        timeFormat.setTimeZone(zone);
        dateFormat.setTimeZone(zone);
        updateClock();
        if (running) {
            removeCallbacks(clockTick);
            post(clockTick);
        }
    }

    /** Weather line text; empty hides the line (when there is no icon either). */
    void setWeatherText(CharSequence text) {
        weatherText.setText(text == null ? "" : text);
        updateWeatherVisibility();
    }

    /** Weather icon shown before the text; null hides it. */
    void setWeatherIcon(Drawable icon) {
        weatherIcon.setImageDrawable(icon);
        weatherIcon.setVisibility(icon == null ? GONE : VISIBLE);
        updateWeatherVisibility();
    }

    private void updateWeatherVisibility() {
        boolean any = !TextUtils.isEmpty(weatherText.getText()) || weatherIcon.getDrawable() != null;
        weatherRow.setVisibility(any ? VISIBLE : GONE);
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFormat.format(now));
        date.setText(dateFormat.format(now));
    }

    private void placeBlock() {
        block.setTranslationX(drift.x() * getWidth());
        block.setTranslationY(drift.y() * getHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) placeBlock();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateRunning();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        updateRunning();
    }

    private void updateRunning() {
        boolean shouldRun = isAttachedToWindow() && isShown();
        if (shouldRun == running) return;
        running = shouldRun;
        if (running) {
            updateClock();
            post(clockTick);
            postDelayed(driftTick, OverlayDrift.INTERVAL_MS);
        } else {
            removeCallbacks(clockTick);
            removeCallbacks(driftTick);
            block.animate().cancel();
        }
    }
}
