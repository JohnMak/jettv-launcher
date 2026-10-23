package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** SharedPreferences persistence of {@link LauncherSettings}; every {@link #load} reads the current state. */
final class LauncherSettingsStore {
    private static final String FILE = "launcher_settings";
    private final SharedPreferences prefs;

    LauncherSettingsStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    LauncherSettings load() {
        LauncherSettings d = LauncherSettings.DEFAULTS;
        return LauncherSettings.fromStored(
                prefs.getBoolean("screensaver_enabled", d.screensaverEnabled),
                prefs.getInt("screensaver_delay_min", d.screensaverDelayMin),
                prefs.getString("screensaver_type", d.screensaverType.name()),
                prefs.getBoolean("dim_enabled", d.dimEnabled),
                prefs.getInt("dim_delay_sec", d.dimDelaySec),
                prefs.getBoolean("vpn_button_visible", d.vpnButtonVisible),
                prefs.getString("vpn_package", d.vpnPackage),
                prefs.getInt("ui_scale_percent", d.uiScalePercent),
                prefs.getInt("preview_scale_percent", d.previewScalePercent));
    }

    void save(LauncherSettings s) {
        prefs.edit()
                .putBoolean("screensaver_enabled", s.screensaverEnabled)
                .putInt("screensaver_delay_min", s.screensaverDelayMin)
                .putString("screensaver_type", s.screensaverType.name())
                .putBoolean("dim_enabled", s.dimEnabled)
                .putInt("dim_delay_sec", s.dimDelaySec)
                .putBoolean("vpn_button_visible", s.vpnButtonVisible)
                .putString("vpn_package", s.vpnPackage)
                .putInt("ui_scale_percent", s.uiScalePercent)
                .putInt("preview_scale_percent", s.previewScalePercent)
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
