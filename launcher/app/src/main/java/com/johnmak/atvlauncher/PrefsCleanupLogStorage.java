package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** {@link CleanupLog.Storage} over SharedPreferences. */
final class PrefsCleanupLogStorage implements CleanupLog.Storage {
    private static final String FILE = "cleanup_log";
    private static final String KEY = "log";
    private final SharedPreferences prefs;

    PrefsCleanupLogStorage(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override public String load() { return prefs.getString(KEY, ""); }

    @Override public void save(String text) { prefs.edit().putString(KEY, text).apply(); }
}
