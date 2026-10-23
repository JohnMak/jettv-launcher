package com.alexmak.atvlauncher;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * The system screensaver switch (Settings.Secure {@code screensaver_enabled}). Writing it needs
 * WRITE_SECURE_SETTINGS, granted by {@code pm grant} in tools/build-and-install.sh; this is the single place
 * that touches it.
 */
final class SystemScreensaver {
    private static final String TAG = "AtvScreensaver";
    private static final String KEY = "screensaver_enabled";

    private SystemScreensaver() {}

    /** False when the permission is missing (boundary error); the caller tells the user. */
    static boolean apply(Context context, boolean enabled) {
        try {
            Settings.Secure.putInt(context.getContentResolver(), KEY, enabled ? 1 : 0);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS", e);
            return false;
        }
    }
}
