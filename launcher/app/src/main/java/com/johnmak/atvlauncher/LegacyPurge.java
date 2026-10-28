package com.johnmak.atvlauncher;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * One-time cleanup of data removed features left on the device:
 * <ul>
 * <li>the removed Kinopoisk feature's lookup cache file and its {@code kinopoisk} preferences (the token), removed
 * together with {@link SettingsImport}, bead androidtv-g7m;
 * <li>the removed private-manifest art source's on-disk cache directory ({@code art-cache/}: {@code manifest.json}
 * plus {@code images/}), superseded by the RAM-only Wallhaven source, bead jm-works-jettv-launcher-63j.
 * </ul>
 * Every step is independently best-effort (a failure on one does not block the others) and idempotent: once the data
 * is gone, a repeat run finds nothing left to delete.
 */
final class LegacyPurge {
    private static final String TAG = "LegacyPurge";
    static final String CACHE_FILE = "kinopoisk_cache.json";
    static final String PREFS_FILE = "kinopoisk";
    static final String ART_CACHE_DIR = "art-cache";

    private LegacyPurge() {
    }

    static void run(Context context) {
        File cache = new File(context.getFilesDir(), CACHE_FILE);
        if (cache.exists() && !cache.delete()) Log.w(TAG, "could not delete " + cache);
        context.deleteSharedPreferences(PREFS_FILE);
        deleteRecursive(new File(context.getFilesDir(), ART_CACHE_DIR));
    }

    /**
     * Best-effort recursive delete of {@code file} (or no-op when it does not exist): children before parent, so a
     * non-empty directory is still removed. Logs but never throws on a failed removal.
     */
    static void deleteRecursive(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        if (!file.delete()) Log.w(TAG, "could not delete " + file);
    }
}
