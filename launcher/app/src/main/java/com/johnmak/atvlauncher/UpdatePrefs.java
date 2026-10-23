package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences "updater": when the feed last answered and the install that is in flight. */
final class UpdatePrefs implements UpdateController.Store {
    private static final String FILE = "updater";
    private static final String LAST_CHECK = "lastCheckAt";
    private static final String PENDING_VERSION = "pendingVersion";
    private static final String PENDING_AT = "pendingAt";

    private final SharedPreferences prefs;

    UpdatePrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override public long lastCheckAt() { return prefs.getLong(LAST_CHECK, 0); }

    @Override public void saveLastCheckAt(long atMs) { prefs.edit().putLong(LAST_CHECK, atMs).apply(); }

    @Override
    public UpdateController.Pending pending() {
        String version = prefs.getString(PENDING_VERSION, null);
        return version == null ? null : new UpdateController.Pending(version, prefs.getLong(PENDING_AT, 0));
    }

    /** Synchronous: the process may be killed by the installation right after this returns. */
    @Override
    public void savePending(UpdateController.Pending pending) {
        prefs.edit().putString(PENDING_VERSION, pending.version).putLong(PENDING_AT, pending.atMs).commit();
    }

    @Override public void clearPending() { prefs.edit().remove(PENDING_VERSION).remove(PENDING_AT).commit(); }
}
