package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** SharedPreferences persistence of {@link LauncherSettings}; every {@link #load} reads the current state. */
final class LauncherSettingsStore {
    static final String FILE = "launcher_settings";
    static final String LEGACY_SCREENSAVER_ENABLED = "screensaver_enabled";
    static final String KEY_SCREENSAVER_DELAY_MIN = "screensaver_delay_min";
    static final String KEY_SCREENSAVER_TYPE = "screensaver_type";
    static final String LEGACY_DIM_ENABLED = "dim_enabled";
    static final String KEY_DIM_DELAY_SEC = "dim_delay_sec";
    static final String KEY_VPN_BUTTON_VISIBLE = "vpn_button_visible";
    static final String KEY_VPN_PACKAGE = "vpn_package";
    static final String KEY_UI_SCALE_PERCENT = "ui_scale_percent";
    static final String KEY_PREVIEW_SCALE_PERCENT = "preview_scale_percent";
    static final String KEY_ANIMATIONS_OFF = "animations_off";
    private final SharedPreferences prefs;

    LauncherSettingsStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Reads the current state. Legacy «enabled» flags (old format, or written by {@link SettingsImport}) are
     * migrated once: a disabled stage becomes delay 0, then the legacy keys are removed.
     */
    LauncherSettings load() {
        LauncherSettings d = LauncherSettings.DEFAULTS;
        LauncherSettings s = LauncherSettings.fromStored(
                prefs.getBoolean(LEGACY_SCREENSAVER_ENABLED, true),
                prefs.getInt(KEY_SCREENSAVER_DELAY_MIN, d.screensaverDelayMin),
                prefs.getString(KEY_SCREENSAVER_TYPE, d.screensaverType.name()),
                prefs.getBoolean(LEGACY_DIM_ENABLED, true),
                prefs.getInt(KEY_DIM_DELAY_SEC, d.dimDelaySec),
                prefs.getBoolean(KEY_VPN_BUTTON_VISIBLE, d.vpnButtonVisible),
                prefs.getString(KEY_VPN_PACKAGE, d.vpnPackage),
                prefs.getInt(KEY_UI_SCALE_PERCENT, d.uiScalePercent),
                prefs.getInt(KEY_PREVIEW_SCALE_PERCENT, d.previewScalePercent),
                prefs.getBoolean(KEY_ANIMATIONS_OFF, d.animationsOff));
        if (prefs.contains(LEGACY_SCREENSAVER_ENABLED) || prefs.contains(LEGACY_DIM_ENABLED)) save(s);
        return s;
    }

    /** Writes the settings and drops the legacy «enabled» keys. */
    void save(LauncherSettings s) {
        prefs.edit()
                .remove(LEGACY_SCREENSAVER_ENABLED)
                .remove(LEGACY_DIM_ENABLED)
                .putInt(KEY_SCREENSAVER_DELAY_MIN, s.screensaverDelayMin)
                .putString(KEY_SCREENSAVER_TYPE, s.screensaverType.name())
                .putInt(KEY_DIM_DELAY_SEC, s.dimDelaySec)
                .putBoolean(KEY_VPN_BUTTON_VISIBLE, s.vpnButtonVisible)
                .putString(KEY_VPN_PACKAGE, s.vpnPackage)
                .putInt(KEY_UI_SCALE_PERCENT, s.uiScalePercent)
                .putInt(KEY_PREVIEW_SCALE_PERCENT, s.previewScalePercent)
                .putBoolean(KEY_ANIMATIONS_OFF, s.animationsOff)
                .apply();
    }

    /** Packages hidden from a feed row; apps not in the set (including newly installed ones) are shown. */
    Set<String> excluded(FeedSource source) {
        return new HashSet<>(prefs.getStringSet(source.prefsKey, Collections.emptySet()));
    }

    void saveExcluded(FeedSource source, Set<String> packages) {
        prefs.edit().putStringSet(source.prefsKey, new HashSet<>(packages)).apply();
    }
}
