package com.alexmak.atvlauncher;

import android.content.Context;
import android.content.res.Configuration;

/**
 * The launcher's UI scale: 70%..130% in 5% steps, applied to an activity by scaling its screen density
 * ({@link #wrap} in {@code attachBaseContext}), so every dp and sp size follows while the pixel size of the screen
 * (and the font scale) stays. The rules are pure; only {@link #wrap} touches Android.
 */
final class UiScale {
    static final int DEFAULT_PERCENT = 100;
    static final int[] PERCENTS = {70, 75, 80, 85, 90, 95, 100, 105, 110, 115, 120, 125, 130};

    private UiScale() {
    }

    static boolean isValid(int percent) {
        return indexOf(percent) >= 0;
    }

    /** Boundary for persisted values: anything outside the list becomes the default. */
    static int fromStored(int percent) {
        return isValid(percent) ? percent : DEFAULT_PERCENT;
    }

    /** One step up; stays at the maximum. */
    static int next(int percent) {
        return PERCENTS[Math.min(PERCENTS.length - 1, indexOf(percent) + 1)];
    }

    /** One step down; stays at the minimum. */
    static int prev(int percent) {
        return PERCENTS[Math.max(0, indexOf(percent) - 1)];
    }

    /** Density (dpi) that makes every dp {@code percent}% of its size at {@code baseDensityDpi}. */
    static int densityDpi(int baseDensityDpi, int percent) {
        return Math.round(baseDensityDpi * percent / 100f);
    }

    /** {@code base} with the stored scale applied to its density; call from {@code attachBaseContext}. */
    static Context wrap(Context base) {
        int percent = new LauncherSettingsStore(base).load().uiScalePercent;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.densityDpi = densityDpi(config.densityDpi, percent);
        return base.createConfigurationContext(config);
    }

    private static int indexOf(int percent) {
        for (int i = 0; i < PERCENTS.length; i++) if (PERCENTS[i] == percent) return i;
        return -1;
    }
}
