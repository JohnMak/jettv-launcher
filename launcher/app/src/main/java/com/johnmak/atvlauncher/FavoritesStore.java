package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Ordered list of favorite app packages, persisted through {@link Storage}.
 * The first run (nothing stored yet) is seeded; afterwards the stored list is the single source of truth.
 */
final class FavoritesStore {
    /** Persistence boundary: one opaque string, null when nothing was ever written. */
    interface Storage {
        String read();

        void write(String value);
    }

    private final Storage storage;
    private final List<String> packages = new ArrayList<>();

    FavoritesStore(Storage storage, String[] seed) {
        this.storage = storage;
        String stored = storage.read();
        if (stored == null) {
            Collections.addAll(packages, seed);
            save();
        } else if (!stored.isEmpty()) {
            Collections.addAll(packages, stored.split("\n"));
        }
    }

    List<String> list() {
        return Collections.unmodifiableList(packages);
    }

    boolean contains(String pkg) {
        return packages.contains(pkg);
    }

    /** Appends the package; false when it is already a favorite. */
    boolean add(String pkg) {
        if (packages.contains(pkg)) return false;
        packages.add(pkg);
        save();
        return true;
    }

    boolean remove(String pkg) {
        if (!packages.remove(pkg)) return false;
        save();
        return true;
    }

    /** Shifts the package by delta positions (clamped at the ends); returns its new index or -1 if unknown. */
    int move(String pkg, int delta) {
        int from = packages.indexOf(pkg);
        if (from < 0) return -1;
        int to = Math.max(0, Math.min(packages.size() - 1, from + delta));
        if (to != from) {
            packages.remove(from);
            packages.add(to, pkg);
            save();
        }
        return to;
    }

    /** Drops favorites that are no longer launchable. */
    void retainOnly(Set<String> installed) {
        if (packages.retainAll(installed)) save();
    }

    private void save() {
        storage.write(String.join("\n", packages));
    }
}
