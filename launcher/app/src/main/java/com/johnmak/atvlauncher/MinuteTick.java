package com.johnmak.atvlauncher;

import java.util.TimeZone;

/** Pure: when the displayed clock of a timezone next changes its minute. */
final class MinuteTick {
    private static final long MINUTE_MS = 60_000L;

    private MinuteTick() {
    }

    /** Milliseconds from {@code nowMillis} (epoch) to the next minute boundary of {@code zone}'s wall clock; 1..60000. */
    static long delayMillis(long nowMillis, TimeZone zone) {
        long wall = nowMillis + zone.getOffset(nowMillis);
        return MINUTE_MS - Math.floorMod(wall, MINUTE_MS);
    }
}
