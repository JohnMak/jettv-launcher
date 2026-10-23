package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects feed rows while the provider cursors are read and keeps only what can be displayed,
 * so thousands of rows never sit in memory. Rows of packages outside {@code allowed} are skipped; watch-next rows
 * of packages in {@code excludedContinue} and preview rows of packages in {@code excludedRecommendations} too.
 * <p>
 * The mixed row gives every publishing app at least one card (its most popular program) and splits the
 * remaining slots between apps in proportion to how often the user launched them.
 */
final class FeedBuilder {
    static final int WATCH_NEXT_MAX = 20;
    static final int MIXED_MAX = 30;
    private static final int TRIM_AT = 64;

    private static final Comparator<FeedItem> BY_RECENT =
            Comparator.comparingLong((FeedItem i) -> i.engagementMillis).reversed();
    /** Most popular first: weight descending, then channel ascending; stable sort keeps arrival order for the rest. */
    private static final Comparator<FeedItem> BY_POPULARITY =
            Comparator.comparingInt((FeedItem i) -> i.weight).reversed()
                    .thenComparingLong(i -> i.channelId);

    private final Set<String> allowed;
    private final Set<String> excludedContinue;
    private final Set<String> excludedRecommendations;
    private final Map<String, Long> launches;
    private final List<FeedItem> watchNext = new ArrayList<>();
    private final Map<String, List<FeedItem>> previews = new LinkedHashMap<>();

    /** @param launches launch count per package; packages missing from the map count as never opened. */
    FeedBuilder(Set<String> allowed, Set<String> excludedContinue, Set<String> excludedRecommendations,
                Map<String, Long> launches) {
        this.allowed = allowed;
        this.excludedContinue = excludedContinue;
        this.excludedRecommendations = excludedRecommendations;
        this.launches = launches;
    }

    void addWatchNext(FeedItem item) {
        if (!allowed.contains(item.packageName) || !ExcludedApps.isIncluded(excludedContinue, item.packageName)) return;
        watchNext.add(item);
        if (watchNext.size() >= TRIM_AT) trimWatchNext();
    }

    void addPreview(FeedItem item) {
        if (!allowed.contains(item.packageName) || !ExcludedApps.isIncluded(excludedRecommendations, item.packageName)) return;
        List<FeedItem> list = previews.computeIfAbsent(item.packageName, k -> new ArrayList<>());
        list.add(item);
        if (list.size() >= TRIM_AT) trimPreviews(list);
    }

    /** Most recently watched first; programs without an engagement time go last. */
    List<FeedItem> watchNext() {
        trimWatchNext();
        return new ArrayList<>(watchNext);
    }

    List<FeedItem> mixed() {
        for (List<FeedItem> list : previews.values()) trimPreviews(list);
        Map<String, Integer> quota = allocate();

        // k-th card of an app sits at (k + 0.5) / quota on a 0..1 line; sorting these spreads every app evenly.
        List<Slot> slots = new ArrayList<>();
        for (Map.Entry<String, Integer> e : quota.entrySet()) {
            for (int k = 0; k < e.getValue(); k++) {
                slots.add(new Slot(e.getKey(), (k + 0.5) / e.getValue(), previews.get(e.getKey()).get(k)));
            }
        }
        slots.sort(Comparator.comparingDouble((Slot s) -> s.position)
                .thenComparing(Comparator.comparingLong((Slot s) -> launchesOf(s.pkg)).reversed())
                .thenComparing(s -> s.pkg));
        List<FeedItem> out = new ArrayList<>();
        for (Slot s : slots) out.add(s.item);
        return out;
    }

    private static final class Slot {
        final String pkg;
        final double position;
        final FeedItem item;

        Slot(String pkg, double position, FeedItem item) {
            this.pkg = pkg;
            this.position = position;
            this.item = item;
        }
    }

    /** Cards per package: 1 each (top apps by launches if there are more apps than slots), rest proportional. */
    private Map<String, Integer> allocate() {
        List<String> pkgs = new ArrayList<>(previews.keySet());
        pkgs.sort(Comparator.comparingLong((String p) -> launchesOf(p)).reversed().thenComparing(p -> p));
        if (pkgs.size() > MIXED_MAX) pkgs = new ArrayList<>(pkgs.subList(0, MIXED_MAX));

        Map<String, Integer> quota = new LinkedHashMap<>();
        for (String p : pkgs) quota.put(p, 1);
        int remaining = MIXED_MAX - pkgs.size();

        while (remaining > 0) {
            List<String> active = new ArrayList<>();
            long total = 0;
            for (String p : pkgs) {
                if (launchesOf(p) > 0 && quota.get(p) < previews.get(p).size()) {
                    active.add(p);
                    total += launchesOf(p);
                }
            }
            if (active.isEmpty()) break;

            Map<String, Double> fraction = new LinkedHashMap<>();
            int given = 0;
            for (String p : active) {
                double exact = (double) remaining * launchesOf(p) / total;
                int whole = (int) Math.min(Math.floor(exact), previews.get(p).size() - quota.get(p));
                quota.merge(p, whole, Integer::sum);
                given += whole;
                fraction.put(p, exact - Math.floor(exact));
            }
            // largest remainder: leftover slots go to the biggest fractions, ties by package name
            List<String> byFraction = new ArrayList<>(active);
            byFraction.sort(Comparator.comparingDouble((String p) -> fraction.get(p)).reversed().thenComparing(p -> p));
            for (String p : byFraction) {
                if (given >= remaining) break;
                if (quota.get(p) < previews.get(p).size()) {
                    quota.merge(p, 1, Integer::sum);
                    given++;
                }
            }
            remaining -= given;
            if (given == 0) break;
        }
        return quota;
    }

    private long launchesOf(String pkg) {
        return launches.getOrDefault(pkg, 0L);
    }

    private void trimWatchNext() {
        watchNext.sort(BY_RECENT);
        while (watchNext.size() > WATCH_NEXT_MAX) watchNext.remove(watchNext.size() - 1);
    }

    private static void trimPreviews(List<FeedItem> list) {
        list.sort(BY_POPULARITY);
        while (list.size() > MIXED_MAX) list.remove(list.size() - 1);
    }
}
