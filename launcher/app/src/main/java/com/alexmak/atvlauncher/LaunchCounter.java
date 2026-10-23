package com.alexmak.atvlauncher;

import java.util.HashMap;
import java.util.Map;

/** Counts launches: transitions of the foreground package into an app (not every activity switch inside it). */
final class LaunchCounter {
    private final Map<String, Long> counts = new HashMap<>();
    private String foreground;

    /** Feed foreground events in chronological order. */
    void onForeground(String pkg) {
        if (pkg.equals(foreground)) return;
        foreground = pkg;
        counts.merge(pkg, 1L, Long::sum);
    }

    Map<String, Long> counts() {
        return counts;
    }
}
