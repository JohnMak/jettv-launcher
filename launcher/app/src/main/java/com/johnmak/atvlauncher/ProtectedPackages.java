package com.johnmak.atvlauncher;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Packages that cleanup must never touch: fixed core set, a prefix rule, plus dynamic additions (IME, persistent, other HOMEs). */
final class ProtectedPackages {
    private static final String PROVIDERS_PREFIX = "com.android.providers.";

    private static final Set<String> BASE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.johnmak.atvlauncher",
            "android",
            "com.android.systemui",
            "com.android.shell",
            "com.android.bluetooth",
            "com.android.tv.settings",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.tv.remote.service")));

    private final Set<String> exact;

    private ProtectedPackages(Set<String> exact) { this.exact = exact; }

    static ProtectedPackages base() { return new ProtectedPackages(BASE); }

    /** A copy that additionally protects {@code extra} (current IME, persistent apps, other HOME holders). */
    ProtectedPackages with(Collection<String> extra) {
        Set<String> merged = new HashSet<>(exact);
        merged.addAll(extra);
        return new ProtectedPackages(Collections.unmodifiableSet(merged));
    }

    boolean contains(String pkg) {
        if (pkg == null) return false;
        return exact.contains(pkg) || (pkg.startsWith(PROVIDERS_PREFIX) && pkg.length() > PROVIDERS_PREFIX.length());
    }
}
