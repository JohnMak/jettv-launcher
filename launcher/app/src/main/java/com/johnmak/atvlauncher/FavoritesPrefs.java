package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences-backed favorites; each call reads the current persisted list. */
final class FavoritesPrefs implements FavoritesStore.Storage {
    private static final String FILE = "favorites";
    private static final String KEY = "packages";

    private final SharedPreferences prefs;

    private FavoritesPrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static FavoritesStore open(Context context) {
        return new FavoritesStore(new FavoritesPrefs(context),
                context.getResources().getStringArray(R.array.favorites_seed));
    }

    @Override
    public String read() {
        return prefs.getString(KEY, null);
    }

    @Override
    public void write(String value) {
        prefs.edit().putString(KEY, value).apply();
    }
}
