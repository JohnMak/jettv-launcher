package com.johnmak.atvlauncher;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link SharedPreferences} test double backed by a plain {@link Map}. It implements exactly the
 * surface {@link LauncherSettingsStore} touches (the getters, contains, edit and the matching Editor put/remove/apply);
 * every other method of the interface throws so an accidental new dependency is loud rather than silently wrong.
 *
 * <p>Semantics mirror the real class closely enough for a persistence round-trip: an {@link Editor} buffers its
 * mutations and only mutates the backing store on {@link Editor#apply()}/{@link Editor#commit()}, so a
 * "simulated restart" (construct a fresh store over the same instance, then {@code load()}) sees exactly what was
 * committed. The same instance survives across stores, modelling the on-disk prefs file.
 */
final class FakeSharedPreferences implements SharedPreferences {
    /** Sentinel distinguishing a buffered removal from a buffered null value. */
    private static final Object REMOVED = new Object();

    private final Map<String, Object> store = new HashMap<>();

    @Override public Map<String, ?> getAll() {
        return new HashMap<>(store);
    }

    @Override public String getString(String key, String defValue) {
        Object v = store.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = store.get(key);
        return v instanceof Set ? new HashSet<>((Set<String>) v) : defValues;
    }

    @Override public int getInt(String key, int defValue) {
        Object v = store.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override public long getLong(String key, long defValue) {
        Object v = store.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override public float getFloat(String key, float defValue) {
        Object v = store.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override public boolean getBoolean(String key, boolean defValue) {
        Object v = store.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override public boolean contains(String key) {
        return store.containsKey(key);
    }

    @Override public Editor edit() {
        return new FakeEditor();
    }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException("FakeSharedPreferences does not model change listeners");
    }

    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        throw new UnsupportedOperationException("FakeSharedPreferences does not model change listeners");
    }

    private final class FakeEditor implements Editor {
        private final Map<String, Object> pending = new HashMap<>();
        private boolean clear;

        @Override public Editor putString(String key, String value) {
            pending.put(key, value == null ? REMOVED : value);
            return this;
        }

        @Override public Editor putStringSet(String key, Set<String> values) {
            pending.put(key, values == null ? REMOVED : new HashSet<>(values));
            return this;
        }

        @Override public Editor putInt(String key, int value) {
            pending.put(key, value);
            return this;
        }

        @Override public Editor putLong(String key, long value) {
            pending.put(key, value);
            return this;
        }

        @Override public Editor putFloat(String key, float value) {
            pending.put(key, value);
            return this;
        }

        @Override public Editor putBoolean(String key, boolean value) {
            pending.put(key, value);
            return this;
        }

        @Override public Editor remove(String key) {
            pending.put(key, REMOVED);
            return this;
        }

        @Override public Editor clear() {
            clear = true;
            return this;
        }

        @Override public boolean commit() {
            flush();
            return true;
        }

        @Override public void apply() {
            flush();
        }

        private void flush() {
            if (clear) store.clear();
            for (Map.Entry<String, Object> e : pending.entrySet()) {
                if (e.getValue() == REMOVED) store.remove(e.getKey());
                else store.put(e.getKey(), e.getValue());
            }
            pending.clear();
            clear = false;
        }
    }
}
