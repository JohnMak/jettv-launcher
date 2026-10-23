package com.johnmak.atvlauncher;

import java.util.regex.Pattern;

/** A validated Android package name; the only way a package id reaches a shell line. */
final class PackageName {
    static final int MAX_LENGTH = 255;
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");

    private final String value;

    private PackageName(String value) { this.value = value; }

    static FetchResult<PackageName> parse(String raw) {
        if (raw == null) return FetchResult.err("package name is null");
        if (raw.length() > MAX_LENGTH) return FetchResult.err("package name longer than " + MAX_LENGTH);
        if (!FORMAT.matcher(raw).matches()) return FetchResult.err("invalid package name: " + raw);
        return FetchResult.ok(new PackageName(raw));
    }

    String value() { return value; }

    @Override public boolean equals(Object o) { return o instanceof PackageName && ((PackageName) o).value.equals(value); }

    @Override public int hashCode() { return value.hashCode(); }

    @Override public String toString() { return value; }
}
