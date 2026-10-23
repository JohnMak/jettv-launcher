package com.alexmak.atvlauncher;

/**
 * Pure rule for "fetch the forecast now?". There is nothing to fetch without a stored city; a missing snapshot or one
 * of another city is always fetched. Otherwise the snapshot's age decides: when the home screen becomes active
 * (launcher start, return from another app) it must be at least {@link #ACTIVATION_MIN_AGE_MS} old, so quick
 * back-and-forth does not hit the weather API each time; the hourly tick needs {@link #HOURLY_MS}.
 */
final class WeatherRefreshPolicy {
    enum Trigger {
        /** The home screen became active (onResume). */
        ACTIVATED,
        /** The hourly timer fired while the home screen is active. */
        HOURLY
    }

    static final long ACTIVATION_MIN_AGE_MS = 10L * 60 * 1000;
    static final long HOURLY_MS = 60L * 60 * 1000;

    private WeatherRefreshPolicy() {
    }

    static boolean shouldRefresh(Trigger trigger, Weather cached, String cachedKey, GeoLocation location, long nowMillis) {
        if (location == null) return false;
        if (cached == null || !location.key().equals(cachedKey)) return true;
        long age = nowMillis - cached.fetchedAtMillis;
        return age >= (trigger == Trigger.ACTIVATED ? ACTIVATION_MIN_AGE_MS : HOURLY_MS);
    }
}
