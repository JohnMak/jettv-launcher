package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What cleanup did to which package and when, so it can be reverted. Immutable; one line per package. */
final class CleanupLog {
    static final class Entry {
        final String pkg;
        final CleanupProfile.Action action;
        final long timestampMillis;

        Entry(String pkg, CleanupProfile.Action action, long timestampMillis) {
            this.pkg = pkg;
            this.action = action;
            this.timestampMillis = timestampMillis;
        }
    }

    /** Persistence port; implemented over SharedPreferences by {@link PrefsCleanupLogStorage}. */
    interface Storage {
        /** The saved text, or an empty string when nothing was saved. */
        String load();

        void save(String text);
    }

    private final Map<String, Entry> byPkg;

    private CleanupLog(Map<String, Entry> byPkg) { this.byPkg = byPkg; }

    static CleanupLog empty() { return new CleanupLog(Collections.<String, Entry>emptyMap()); }

    List<Entry> entries() { return Collections.unmodifiableList(new ArrayList<>(byPkg.values())); }

    Entry get(String pkg) { return byPkg.get(pkg); }

    CleanupLog record(String pkg, CleanupProfile.Action action, long timestampMillis) {
        Map<String, Entry> next = new LinkedHashMap<>(byPkg);
        next.remove(pkg);
        next.put(pkg, new Entry(pkg, action, timestampMillis));
        return new CleanupLog(next);
    }

    CleanupLog forget(String pkg) {
        Map<String, Entry> next = new LinkedHashMap<>(byPkg);
        next.remove(pkg);
        return new CleanupLog(next);
    }

    /** One line per entry: {@code action TAB package TAB timestampMillis}. */
    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : byPkg.values()) {
            sb.append(e.action.word()).append('\t').append(e.pkg).append('\t').append(e.timestampMillis).append('\n');
        }
        return sb.toString();
    }

    static FetchResult<CleanupLog> parse(String text) {
        if (text == null) return FetchResult.err("cleanup log text is null");
        Map<String, Entry> map = new LinkedHashMap<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] f = lines[i].split("\t", -1);
            if (f.length != 3) return FetchResult.err("cleanup log line " + (i + 1) + ": expected 3 fields");
            CleanupProfile.Action action = CleanupProfile.Action.fromWord(f[0]);
            if (action == null) return FetchResult.err("cleanup log line " + (i + 1) + ": unknown action " + f[0]);
            FetchResult<PackageName> pkg = PackageName.parse(f[1]);
            if (!pkg.isOk()) return FetchResult.err("cleanup log line " + (i + 1) + ": " + pkg.error());
            long ts;
            try {
                ts = Long.parseLong(f[2]);
            } catch (NumberFormatException ex) {
                return FetchResult.err("cleanup log line " + (i + 1) + ": bad timestamp " + f[2]);
            }
            map.put(f[1], new Entry(f[1], action, ts));
        }
        return FetchResult.ok(new CleanupLog(map));
    }

    static FetchResult<CleanupLog> loadFrom(Storage storage) { return parse(storage.load()); }

    void saveTo(Storage storage) { storage.save(serialize()); }
}
