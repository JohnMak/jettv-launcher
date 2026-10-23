package com.alexmak.atvlauncher;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Rules for a set of excluded packages: an app is shown unless it is in the set, so apps installed later are
 * included by default. Sets are never mutated; every change returns a new one.
 */
final class ExcludedApps {
    private ExcludedApps() {
    }

    static boolean isIncluded(Set<String> excluded, String pkg) {
        return !excluded.contains(pkg);
    }

    /** {@code excluded} with {@code pkg} flipped. */
    static Set<String> toggled(Set<String> excluded, String pkg) {
        Set<String> next = new HashSet<>(excluded);
        if (!next.remove(pkg)) next.add(pkg);
        return next;
    }

    /** How many of the installed packages are not excluded (excluded packages that are gone do not count). */
    static int includedCount(Collection<String> installed, Set<String> excluded) {
        int n = 0;
        for (String pkg : installed) if (isIncluded(excluded, pkg)) n++;
        return n;
    }
}
