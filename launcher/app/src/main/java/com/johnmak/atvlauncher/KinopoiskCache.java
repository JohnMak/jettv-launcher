package com.johnmak.atvlauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disk cache of Kinopoisk lookups, one small JSON file. A found title is fresh for 7 days, a miss for 24 hours;
 * failed lookups are never put here. Keeps at most {@value #MAX_ENTRIES} entries (oldest dropped on save).
 * Blocking file access: call from a worker thread. Thread safe.
 */
final class KinopoiskCache {
    static final long HIT_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    static final long MISS_TTL_MS = 24L * 60 * 60 * 1000;
    static final int MAX_ENTRIES = 400;
    /** File format / matching rules version; a file of another version is discarded (its matches used other rules). */
    private static final String VERSION_KEY = "_v";
    private static final int VERSION = 2;

    private static final class Entry {
        final long savedAt;
        final KinopoiskInfo info;

        Entry(long savedAt, KinopoiskInfo info) {
            this.savedAt = savedAt;
            this.info = info;
        }
    }

    private final File file;
    private final Map<String, Entry> entries;

    private KinopoiskCache(File file, Map<String, Entry> entries) {
        this.file = file;
        this.entries = entries;
    }

    static KinopoiskCache empty(File file) {
        return new KinopoiskCache(file, new LinkedHashMap<>());
    }

    /** Whether an entry saved at {@code savedAt} may still be used at {@code now} (a clock set back makes it stale). */
    static boolean fresh(KinopoiskInfo info, long savedAt, long now) {
        long age = now - savedAt;
        return age >= 0 && age < (info.isEmpty() ? MISS_TTL_MS : HIT_TTL_MS);
    }

    /** Reads the file; a missing file is an empty cache, an unreadable one is an error. */
    static FetchResult<KinopoiskCache> load(File file) {
        Map<String, Entry> map = new LinkedHashMap<>();
        if (!file.exists()) return FetchResult.ok(empty(file));
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            if (root.optInt(VERSION_KEY, 0) != VERSION) return FetchResult.ok(empty(file));
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                if (key.equals(VERSION_KEY)) continue;
                JSONObject e = root.getJSONObject(key);
                map.put(key, new Entry(e.getLong("t"),
                        new KinopoiskInfo(e.getString("kp"), e.getString("imdb"))));
            }
            return FetchResult.ok(new KinopoiskCache(file, map));
        } catch (IOException | JSONException e) {
            return FetchResult.err("kinopoisk cache unreadable: " + e.getMessage());
        }
    }

    /** The stored info if it is still fresh at {@code now}; null when absent or stale. */
    synchronized KinopoiskInfo get(String key, long now) {
        Entry e = entries.get(key);
        return e != null && fresh(e.info, e.savedAt, now) ? e.info : null;
    }

    synchronized void put(String key, KinopoiskInfo info, long now) {
        entries.remove(key);
        entries.put(key, new Entry(now, info));
    }

    /** Writes the file (temp file + rename); drops the oldest entries beyond the limit first. */
    synchronized FetchResult<Boolean> save() {
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort((a, b) -> Long.compare(a.getValue().savedAt, b.getValue().savedAt));
        for (int i = 0; i < sorted.size() - MAX_ENTRIES; i++) entries.remove(sorted.get(i).getKey());
        try {
            JSONObject root = new JSONObject().put(VERSION_KEY, VERSION);
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                root.put(e.getKey(), new JSONObject()
                        .put("t", e.getValue().savedAt)
                        .put("kp", e.getValue().info.kp)
                        .put("imdb", e.getValue().info.imdb));
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return FetchResult.ok(Boolean.TRUE);
        } catch (IOException | JSONException e) {
            return FetchResult.err("kinopoisk cache not saved: " + e.getMessage());
        }
    }

    synchronized int size() {
        return entries.size();
    }
}
