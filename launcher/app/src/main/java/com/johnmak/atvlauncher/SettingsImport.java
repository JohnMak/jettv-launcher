package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time settings import used to migrate from the old package; a temporary migration path, to be removed (bead androidtv-g7m).
 * {@code getExternalFilesDir(null)/import/settings.json} is parsed and validated against a strict whitelist; only a fully valid file is written.
 */
final class SettingsImport {
    private static final String TAG = "SettingsImport";
    private static final String DIR = "import";
    private static final String FILE_NAME = "settings.json";
    private static final String REJECTED_NAME = "settings.rejected.json";
    private static final int VERSION = 1;
    private static final long MAX_BYTES = 1 << 20;

    enum Type { STRING, INT, LONG, BOOL, FLOAT, STRINGSET }

    /** One validated value; {@code value} is String, Integer, Long, Boolean, Float or Set&lt;String&gt; matching {@code type}. */
    static final class Entry {
        final String file;
        final String key;
        final Type type;
        final Object value;

        Entry(String file, String key, Type type, Object value) {
            this.file = file;
            this.key = key;
            this.type = type;
            this.value = value;
        }
    }

    /** Everything to write; empty is a valid plan. */
    static final class ImportPlan {
        final List<Entry> entries;

        ImportPlan(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    /** file -> key -> type. */
    static final Map<String, Map<String, Type>> WHITELIST = buildWhitelist();

    private static Map<String, Map<String, Type>> buildWhitelist() {
        Map<String, Map<String, Type>> w = new LinkedHashMap<>();
        Map<String, Type> fav = new LinkedHashMap<>();
        fav.put(FavoritesPrefs.KEY, Type.STRING);
        w.put(FavoritesPrefs.FILE, fav);

        Map<String, Type> ls = new LinkedHashMap<>();
        ls.put(LauncherSettingsStore.LEGACY_SCREENSAVER_ENABLED, Type.BOOL);
        ls.put(LauncherSettingsStore.KEY_SCREENSAVER_DELAY_MIN, Type.INT);
        ls.put(LauncherSettingsStore.KEY_SCREENSAVER_TYPE, Type.STRING);
        ls.put(LauncherSettingsStore.LEGACY_DIM_ENABLED, Type.BOOL);
        ls.put(LauncherSettingsStore.KEY_DIM_DELAY_SEC, Type.INT);
        ls.put(LauncherSettingsStore.KEY_VPN_BUTTON_VISIBLE, Type.BOOL);
        ls.put(LauncherSettingsStore.KEY_VPN_PACKAGE, Type.STRING);
        ls.put(LauncherSettingsStore.KEY_UI_SCALE_PERCENT, Type.INT);
        ls.put(LauncherSettingsStore.KEY_PREVIEW_SCALE_PERCENT, Type.INT);
        ls.put(LauncherSettingsStore.KEY_ANIMATIONS_OFF, Type.BOOL);
        ls.put(FeedSource.RECOMMENDATIONS.prefsKey, Type.STRINGSET);
        ls.put(FeedSource.CONTINUE.prefsKey, Type.STRINGSET);
        w.put(LauncherSettingsStore.FILE, ls);

        Map<String, Type> wp = new LinkedHashMap<>();
        wp.put(WeatherPrefs.G + "lat", Type.LONG);
        wp.put(WeatherPrefs.G + "lon", Type.LONG);
        wp.put(WeatherPrefs.G + "city", Type.STRING);
        wp.put(WeatherPrefs.G + "country", Type.STRING);
        wp.put(WeatherPrefs.G + "tz", Type.STRING);
        w.put(WeatherPrefs.FILE, wp);
        return Collections.unmodifiableMap(w);
    }

    private SettingsImport() {}

    // ---- pure parser / validator ----

    static FetchResult<ImportPlan> parse(String json) {
        if (json == null) return FetchResult.err("json is null");
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            return FetchResult.err("broken JSON: " + e.getMessage());
        }
        for (Iterator<String> it = root.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals("version") && !k.equals("prefs")) return FetchResult.err("unknown top-level key: " + k);
        }
        Object version = root.opt("version");
        if (!(version instanceof Integer)) return FetchResult.err("missing or non-integer version");
        if ((Integer) version != VERSION) return FetchResult.err("unsupported version: " + version);
        JSONObject prefs = root.optJSONObject("prefs");
        if (prefs == null) return FetchResult.err("missing prefs object");

        List<Entry> entries = new ArrayList<>();
        for (Iterator<String> files = prefs.keys(); files.hasNext(); ) {
            String file = files.next();
            Map<String, Type> allowed = WHITELIST.get(file);
            if (allowed == null) return FetchResult.err("unknown prefs file: " + file);
            JSONObject keys = prefs.optJSONObject(file);
            if (keys == null) return FetchResult.err("prefs file is not an object: " + file);
            for (Iterator<String> it = keys.keys(); it.hasNext(); ) {
                String key = it.next();
                Type expected = allowed.get(key);
                if (expected == null) return FetchResult.err("unknown key: " + file + "/" + key);
                JSONObject cell = keys.optJSONObject(key);
                if (cell == null) return FetchResult.err("entry is not an object: " + file + "/" + key);
                FetchResult<Entry> e = parseEntry(file, key, expected, cell);
                if (!e.isOk()) return e.propagate();
                entries.add(e.value());
            }
        }
        return FetchResult.ok(new ImportPlan(entries));
    }

    private static FetchResult<Entry> parseEntry(String file, String key, Type expected, JSONObject cell) {
        String where = file + "/" + key;
        for (Iterator<String> it = cell.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals("type") && !k.equals("value")) return FetchResult.err("unknown field " + k + " in " + where);
        }
        Object typeName = cell.opt("type");
        if (!(typeName instanceof String)) return FetchResult.err("missing type: " + where);
        Type declared = typeOf((String) typeName);
        if (declared == null) return FetchResult.err("unknown type '" + typeName + "': " + where);
        if (declared != expected) return FetchResult.err("wrong type for " + where + ": expected " + expected + ", got " + declared);
        if (!cell.has("value")) return FetchResult.err("missing value: " + where);
        Object raw = cell.opt("value");
        Object value = convert(expected, raw);
        if (value == null) return FetchResult.err("bad " + expected + " value: " + where);
        return FetchResult.ok(new Entry(file, key, expected, value));
    }

    private static Type typeOf(String name) {
        switch (name) {
            case "string": return Type.STRING;
            case "int": return Type.INT;
            case "long": return Type.LONG;
            case "bool": return Type.BOOL;
            case "float": return Type.FLOAT;
            case "stringset": return Type.STRINGSET;
            default: return null;
        }
    }

    /** The typed value, or null when {@code raw} does not strictly match {@code type}. */
    private static Object convert(Type type, Object raw) {
        switch (type) {
            case STRING:
                return raw instanceof String ? raw : null;
            case BOOL:
                return raw instanceof Boolean ? raw : null;
            case INT:
                return raw instanceof Integer ? raw : null;
            case LONG:
                return raw instanceof Integer ? Long.valueOf(((Integer) raw).longValue()) : raw instanceof Long ? raw : null;
            case FLOAT:
                if (!(raw instanceof Number)) return null;
                float f = ((Number) raw).floatValue();
                return Float.isNaN(f) || Float.isInfinite(f) ? null : Float.valueOf(f);
            case STRINGSET:
                if (!(raw instanceof JSONArray)) return null;
                JSONArray a = (JSONArray) raw;
                Set<String> set = new HashSet<>();
                for (int i = 0; i < a.length(); i++) {
                    Object o = a.opt(i);
                    if (!(o instanceof String)) return null;
                    set.add((String) o);
                }
                return set;
            default:
                return null;
        }
    }

    // ---- thin Android side ----

    /** Call first thing in onCreate, before any prefs are read. */
    static void run(Context context) {
        File dir = new File(context.getExternalFilesDir(null), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "cannot create " + dir);
        File file = new File(dir, FILE_NAME);
        if (!file.isFile()) return;

        FetchResult<String> text = read(file);
        FetchResult<ImportPlan> plan = text.isOk() ? parse(text.value()) : text.propagate();
        if (plan.isOk()) {
            String failure = write(context, plan.value());
            if (failure == null) {
                if (!file.delete()) Log.w(TAG, "imported but could not delete " + file);
                else Log.i(TAG, "imported " + plan.value().entries.size() + " values");
                return;
            }
            Log.w(TAG, "import failed: " + failure);
            reject(file, dir);
            return;
        }
        Log.w(TAG, "import rejected: " + plan.error());
        reject(file, dir);
    }

    private static void reject(File file, File dir) {
        File rejected = new File(dir, REJECTED_NAME);
        if (rejected.exists() && !rejected.delete()) Log.w(TAG, "cannot replace " + rejected);
        if (!file.renameTo(rejected)) Log.w(TAG, "cannot rename to " + rejected);
    }

    private static FetchResult<String> read(File file) {
        if (file.length() > MAX_BYTES) return FetchResult.err("file too large");
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return FetchResult.ok(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return FetchResult.err("read failed: " + e.getMessage());
        }
    }

    /** Null on success, otherwise the failure description. */
    private static String write(Context context, ImportPlan plan) {
        Map<String, SharedPreferences.Editor> editors = new HashMap<>();
        for (Entry e : plan.entries) {
            SharedPreferences.Editor ed = editors.get(e.file);
            if (ed == null) {
                ed = context.getSharedPreferences(e.file, Context.MODE_PRIVATE).edit();
                editors.put(e.file, ed);
            }
            switch (e.type) {
                case STRING: ed.putString(e.key, (String) e.value); break;
                case INT: ed.putInt(e.key, (Integer) e.value); break;
                case LONG: ed.putLong(e.key, (Long) e.value); break;
                case BOOL: ed.putBoolean(e.key, (Boolean) e.value); break;
                case FLOAT: ed.putFloat(e.key, (Float) e.value); break;
                case STRINGSET:
                    @SuppressWarnings("unchecked") Set<String> set = (Set<String>) e.value;
                    ed.putStringSet(e.key, new HashSet<>(set));
                    break;
                default: return "unhandled type " + e.type;
            }
        }
        for (Map.Entry<String, SharedPreferences.Editor> me : editors.entrySet()) {
            if (!me.getValue().commit()) return "commit failed for " + me.getKey();
        }
        return null;
    }
}
