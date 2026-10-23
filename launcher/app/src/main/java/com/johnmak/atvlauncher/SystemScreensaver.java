package com.johnmak.atvlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * The system screensaver settings (Settings.Secure {@code screensaver_components} and {@code screensaver_enabled}).
 * Writing them needs WRITE_SECURE_SETTINGS, granted by {@code pm grant} in tools/build-and-install.sh; this is the
 * single place that touches them. {@link #sync} runs on every start so a stale component (e.g. a removed old
 * package) never makes Android fall back to its own dream.
 */
final class SystemScreensaver {
    private static final String TAG = "AtvScreensaver";
    static final String KEY_COMPONENTS = "screensaver_components";
    static final String KEY_ENABLED = "screensaver_enabled";

    private SystemScreensaver() {}

    static final class Write {
        final String key;
        final String value;

        Write(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /** The writes that bring the system settings in line with us; only keys whose value differs. */
    static List<Write> plan(String currentComponents, String currentEnabled, String ownPackage, int delayMin) {
        String component = ownPackage + "/.StarfieldDream";
        String enabled = delayMin != 0 ? "1" : "0";
        List<Write> writes = new ArrayList<>();
        if (!component.equals(currentComponents)) writes.add(new Write(KEY_COMPONENTS, component));
        if (!enabled.equals(currentEnabled)) writes.add(new Write(KEY_ENABLED, enabled));
        return writes;
    }

    /** False when the permission is missing (boundary error); the caller tells the user. */
    static boolean sync(Context context, LauncherSettings settings) {
        ContentResolver resolver = context.getContentResolver();
        try {
            List<Write> writes = plan(Settings.Secure.getString(resolver, KEY_COMPONENTS),
                    Settings.Secure.getString(resolver, KEY_ENABLED), context.getPackageName(),
                    settings.screensaverDelayMin);
            for (Write w : writes) Settings.Secure.putString(resolver, w.key, w.value);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS", e);
            return false;
        }
    }
}
