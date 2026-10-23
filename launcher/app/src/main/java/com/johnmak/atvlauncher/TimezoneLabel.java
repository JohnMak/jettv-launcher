package com.johnmak.atvlauncher;

import java.util.Arrays;
import java.util.TimeZone;

/** Pure formatting of a timezone as a UTC offset label ("UTC+7", "UTC+5:30"). */
final class TimezoneLabel {
    private TimezoneLabel() {}

    /** True for an id the platform really knows ({@link TimeZone#getTimeZone} would silently turn others into GMT). */
    static boolean isKnown(String id) {
        return Arrays.asList(TimeZone.getAvailableIDs()).contains(id);
    }

    /** Offset of the zone at {@code atMillis} (daylight saving included); {@code id} must be {@link #isKnown known}. */
    static String of(String id, long atMillis) {
        if (!isKnown(id)) throw new IllegalArgumentException("unknown timezone: " + id);
        int minutes = TimeZone.getTimeZone(id).getOffset(atMillis) / 60_000;
        if (minutes == 0) return "UTC";
        int abs = Math.abs(minutes);
        String sign = minutes < 0 ? "−" : "+";
        return abs % 60 == 0
                ? "UTC" + sign + (abs / 60)
                : "UTC" + sign + (abs / 60) + ":" + String.format("%02d", abs % 60);
    }
}
