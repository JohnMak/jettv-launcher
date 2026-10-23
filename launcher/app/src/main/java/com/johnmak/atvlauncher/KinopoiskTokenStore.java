package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Pattern;

/**
 * The Kinopoisk Unofficial API token, entered on the device (settings). Empty = no token: ratings are off.
 * Only a UUID-shaped token is ever stored; {@link #validate} is the single boundary for typed input.
 */
final class KinopoiskTokenStore {
    static final String FILE = "kinopoisk";
    static final String KEY = "token";
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final int VISIBLE_TAIL = 4;

    private final SharedPreferences prefs;

    KinopoiskTokenStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The trimmed token if it is UUID-shaped; an error otherwise (including empty input: clearing is {@link #clear}). */
    static FetchResult<String> validate(String raw) {
        if (raw == null) return FetchResult.err("token is null");
        String token = raw.trim();
        if (token.isEmpty()) return FetchResult.err("token is empty");
        if (!UUID.matcher(token).matches()) return FetchResult.err("token is not a UUID");
        return FetchResult.ok(token);
    }

    /** «••••1234» for a token, empty string for none; the rest of the token never reaches the screen. */
    static String mask(String token) {
        if (token.isEmpty()) return "";
        return "••••" + token.substring(token.length() - VISIBLE_TAIL);
    }

    /** The stored token; empty = not set. */
    String token() {
        String stored = prefs.getString(KEY, "");
        return validate(stored).isOk() ? stored : "";
    }

    FetchResult<String> save(String raw) {
        FetchResult<String> checked = validate(raw);
        if (checked.isOk()) prefs.edit().putString(KEY, checked.value()).apply();
        return checked;
    }

    void clear() {
        prefs.edit().remove(KEY).apply();
    }
}
