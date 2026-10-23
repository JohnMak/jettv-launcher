package com.johnmak.atvlauncher;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * One-time cleanup of data the removed Kinopoisk feature left on the device: its lookup cache file and its
 * {@code kinopoisk} preferences (the token). Removed together with {@link SettingsImport}, bead androidtv-g7m.
 */
final class LegacyPurge {
    private static final String TAG = "LegacyPurge";
    static final String CACHE_FILE = "kinopoisk_cache.json";
    static final String PREFS_FILE = "kinopoisk";

    private LegacyPurge() {
    }

    static void run(Context context) {
        File cache = new File(context.getFilesDir(), CACHE_FILE);
        if (cache.exists() && !cache.delete()) Log.w(TAG, "could not delete " + cache);
        context.deleteSharedPreferences(PREFS_FILE);
    }
}
