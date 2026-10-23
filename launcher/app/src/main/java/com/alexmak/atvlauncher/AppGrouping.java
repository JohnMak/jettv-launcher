package com.alexmak.atvlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Splits apps into titled sections by an explicit package list; the last section takes every other app. */
final class AppGrouping {
    static final class Section<T> {
        final String title;
        final List<T> items;

        Section(String title, List<T> items) {
            this.title = title;
            this.items = items;
        }
    }

    private AppGrouping() {
    }

    /** The apps whose package is in {@code order}, in that order (not installed ones are skipped). */
    static <T> List<T> pick(List<T> apps, Function<T, String> pkg, List<String> order) {
        List<T> out = new ArrayList<>();
        for (String wanted : order) {
            for (T app : apps) {
                if (pkg.apply(app).equals(wanted)) {
                    out.add(app);
                    break;
                }
            }
        }
        return out;
    }

    /** The apps whose package is not in {@code hidden}. */
    static <T> List<T> exclude(List<T> apps, Function<T, String> pkg, Set<String> hidden) {
        List<T> out = new ArrayList<>();
        for (T app : apps) {
            if (!hidden.contains(pkg.apply(app))) out.add(app);
        }
        return out;
    }

    /**
     * @param titles        section titles; one more than {@code groupPackages} (the last one is the catch-all)
     * @param groupPackages packages of each explicit section, in title order
     * @return non-empty sections only, each ordered by launch count (ties by label)
     */
    static <T> List<Section<T>> group(List<T> apps, Function<T, String> pkg, Function<T, String> label,
                                      Map<String, Long> launches, List<String> titles,
                                      List<Set<String>> groupPackages) {
        List<List<T>> buckets = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) buckets.add(new ArrayList<>());
        for (T app : apps) {
            int target = groupPackages.size();
            for (int g = 0; g < groupPackages.size(); g++) {
                if (groupPackages.get(g).contains(pkg.apply(app))) {
                    target = g;
                    break;
                }
            }
            buckets.get(target).add(app);
        }
        List<Section<T>> out = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            List<T> items = buckets.get(i);
            if (items.isEmpty()) continue;
            items.sort(UsageRanking.comparator(pkg, label, launches));
            out.add(new Section<>(titles.get(i), items));
        }
        return out;
    }
}
