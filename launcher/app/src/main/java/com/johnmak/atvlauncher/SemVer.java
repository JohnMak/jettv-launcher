package com.johnmak.atvlauncher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict X.Y.Z version; the range matches the build's versionCode scheme (major*1_000_000 + minor*1_000 + patch). */
final class SemVer implements Comparable<SemVer> {
    private static final String NUM = "(0|[1-9]\\d{0,3})";
    private static final Pattern PATTERN = Pattern.compile(NUM + "\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})");
    private static final int MAX_MAJOR = 2000;

    final int major;
    final int minor;
    final int patch;

    private SemVer(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    static FetchResult<SemVer> parse(String text) {
        if (text == null) return FetchResult.err("version is null");
        Matcher m = PATTERN.matcher(text);
        if (!m.matches()) return FetchResult.err("not X.Y.Z: '" + text + "'");
        int major = Integer.parseInt(m.group(1));
        if (major > MAX_MAJOR) return FetchResult.err("major too large: '" + text + "'");
        return FetchResult.ok(new SemVer(major, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    /** The release part of a build's versionName: "1.2.3" or the base of "1.2.3-dev.4+gabc". */
    static FetchResult<SemVer> ofVersionName(String versionName) {
        if (versionName == null) return FetchResult.err("versionName is null");
        int cut = versionName.indexOf('-');
        return parse(cut < 0 ? versionName : versionName.substring(0, cut));
    }

    @Override
    public int compareTo(SemVer o) {
        if (major != o.major) return Integer.compare(major, o.major);
        if (minor != o.minor) return Integer.compare(minor, o.minor);
        return Integer.compare(patch, o.patch);
    }

    @Override public boolean equals(Object o) {
        return o instanceof SemVer && compareTo((SemVer) o) == 0;
    }

    @Override public int hashCode() {
        return (major * 31 + minor) * 31 + patch;
    }

    @Override public String toString() {
        return major + "." + minor + "." + patch;
    }
}
