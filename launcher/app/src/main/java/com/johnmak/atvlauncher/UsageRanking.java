package com.johnmak.atvlauncher;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Orders apps by launch count (most launched first); ties and never-used apps go alphabetically. */
final class UsageRanking {
    private UsageRanking() {
    }

    static <T> Comparator<T> comparator(Function<T, String> pkg, Function<T, String> label, Map<String, Long> usage) {
        Comparator<T> byUsage = Comparator.comparingLong((T t) -> usage.getOrDefault(pkg.apply(t), 0L)).reversed();
        return byUsage.thenComparing(t -> label.apply(t).toLowerCase(Locale.ROOT));
    }
}
