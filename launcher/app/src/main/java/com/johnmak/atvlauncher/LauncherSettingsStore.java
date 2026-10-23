package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** SharedPreferences persistence of {@link LauncherSettings}; every {@link #load} reads the current state. */
final class LauncherSettingsStore {
    static final String FILE = "launcher_settings";
    static final String KEY_SCREENSAVER_ENABLED = "screensaver_enabled";
    static final String KEY_SCREENSAVER_DELAY_MIN = "screensaver_delay_min";
    static final String KEY_SCREENSAVER_TYPE = "screensaver_type";
    static final String KEY_DIM_ENABLED = "dim_enabled";
    static final String KEY_DIM_DELAY_SEC = "dim_delay_sec";
    static final String KEY_VPN_BUTTON_VISIBLE = "vpn_button_visible";
    static final String KEY_VPN_PACKAGE = "vpn_package";
    static final String KEY_UI_SCALE_PERCENT = "ui_scale_percent";
    static final String KEY_PREVIEW_SCALE_PERCENT = "preview_scale_percent";
    private final SharedPreferences prefs;

    LauncherSettingsStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    LauncherSettings load() {
        LauncherSettings d = LauncherSettings.DEFAULTS;
        return LauncherSettings.fromStored(
                prefs.getBoolean(KEY_SCREENSAVER_ENABLED, d.screensaverEnabled),
                prefs.getInt(KEY_SCREENSAVER_DELAY_MIN, d.screensaverDelayMin),
                prefs.getString(KEY_SCREENSAVER_TYPE, d.screensaverType.name()),
                prefs.getBoolean(KEY_DIM_ENABLED, d.dimEnabled),
                prefs.getInt(KEY_DIM_DELAY_SEC, d.dimDelaySec),
                prefs.getBoolean(KEY_VPN_BUTTON_VISIBLE, d.vpnButtonVisible),
                prefs.getString(KEY_VPN_PACKAGE, d.vpnPackage),
                prefs.getInt(KEY_UI_SCALE_PERCENT, d.uiScalePercent),
                prefs.getInt(KEY_PREVIEW_SCALE_PERCENT, d.previewScalePercent));
    }

    void save(LauncherSettings s) {
        prefs.edit()
                .putBoolean(KEY_SCREENSAVER_ENABLED, s.screensaverEnabled)
                .putInt(KEY_SCREENSAVER_DELAY_MIN, s.screensaverDelayMin)
                .putString(KEY_SCREENSAVER_TYPE, s.screensaverType.name())
                .putBoolean(KEY_DIM_ENABLED, s.dimEnabled)
                .putInt(KEY_DIM_DELAY_SEC, s.dimDelaySec)
                .putBoolean(KEY_VPN_BUTTON_VISIBLE, s.vpnButtonVisible)
                .putString(KEY_VPN_PACKAGE, s.vpnPackage)
                .putInt(KEY_UI_SCALE_PERCENT, s.uiScalePercent)
                .putInt(KEY_PREVIEW_SCALE_PERCENT, s.previewScalePercent)
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
