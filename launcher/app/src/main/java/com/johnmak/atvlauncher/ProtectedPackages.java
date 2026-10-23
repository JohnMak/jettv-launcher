package com.johnmak.atvlauncher;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Packages that cleanup must never touch: fixed core set, prefix rules (providers, network/wifi stacks, vendor
 * platform services), plus dynamic additions (IME, screensaver, accessibility, WebView, persistent apps) that are
 * fully protected and HOME handlers that are {@link Level#DISABLE_ONLY}. Vendor-prefixed packages listed in the
 * matched profile are the curated exception.
 */
final class ProtectedPackages {
    /** How much cleanup may do to a package. */
    enum Level { NONE, DISABLE_ONLY, FULL }

    private static final String PROVIDERS_PREFIX = "com.android.providers.";

    private static final String[] PLATFORM_PREFIXES = {
            "com.android.networkstack", "com.google.android.networkstack", "com.android.wifi"};

    private static final String[] VENDOR_PREFIXES = {
            "com.droidlogic.", "com.amlogic.", "com.mediatek.", "com.mstar.", "com.xgimi."};

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
            "com.google.android.tv.remote.service",
            "com.google.android.gsf",
            "com.google.android.webview",
            "com.google.android.trichromelibrary",
            "com.android.webview",
            "com.google.android.ext.services",
            "android.ext.services",
            "com.android.inputdevices",
            "com.android.externalstorage",
            "com.android.location.fused",
            "com.android.se",
            "com.android.nfc",
            "com.android.server.telecom",
            "com.android.keychain",
            "com.android.certinstaller",
            "com.android.dynsystem",
            "com.android.tv.frameworkpackagestubs")));

    private final Set<String> exact;
    private final Set<String> disableOnly;
    private final Set<String> vendorAllowed;

    private ProtectedPackages(Set<String> exact, Set<String> disableOnly, Set<String> vendorAllowed) {
        this.exact = exact;
        this.disableOnly = disableOnly;
        this.vendorAllowed = vendorAllowed;
    }

    static ProtectedPackages base() {
        return new ProtectedPackages(BASE, Collections.<String>emptySet(), Collections.<String>emptySet());
    }

    private static Set<String> union(Set<String> a, Collection<String> b) {
        Set<String> merged = new HashSet<>(a);
        merged.addAll(b);
        return Collections.unmodifiableSet(merged);
    }

    /** A copy that additionally fully protects {@code extra} (IME, screensaver, accessibility, WebView, persistent apps). */
    ProtectedPackages with(Collection<String> extra) {
        return new ProtectedPackages(union(exact, extra), disableOnly, vendorAllowed);
    }

    /** A copy where {@code pkgs} (HOME handlers) may be disabled or reset but never removed. */
    ProtectedPackages withDisableOnly(Collection<String> pkgs) {
        return new ProtectedPackages(exact, union(disableOnly, pkgs), vendorAllowed);
    }

    /** A copy that waives the vendor-prefix rule (only that rule) for packages the matched profile lists. */
    ProtectedPackages allowingVendor(Collection<String> profilePkgs) {
        return new ProtectedPackages(exact, disableOnly, union(vendorAllowed, profilePkgs));
    }

    Level level(String pkg) {
        if (pkg == null) return Level.NONE;
        if (exact.contains(pkg)) return Level.FULL;
        if (pkg.startsWith(PROVIDERS_PREFIX) && pkg.length() > PROVIDERS_PREFIX.length()) return Level.FULL;
        for (String p : PLATFORM_PREFIXES) if (pkg.startsWith(p)) return Level.FULL;
        if (!vendorAllowed.contains(pkg)) {
            for (String p : VENDOR_PREFIXES) if (pkg.startsWith(p) && pkg.length() > p.length()) return Level.FULL;
        }
        return disableOnly.contains(pkg) ? Level.DISABLE_ONLY : Level.NONE;
    }

    /** True when cleanup must not touch the package at all. */
    boolean contains(String pkg) { return level(pkg) == Level.FULL; }
}
