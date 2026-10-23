package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeatherRefreshPolicyTest {
    private static final GeoLocation A = new GeoLocation(55.75, 37.62, "Москва", "RU", "Europe/Moscow");
    private static final GeoLocation B = new GeoLocation(54.98, 82.9, "Новосибирск", "RU", "Asia/Novosibirsk");
    private static final long T0 = 1_000_000_000L;
    private static final Weather W = new Weather(1, WeatherIcon.SUN, true, "", 2, 0, T0);

    private static boolean due(WeatherRefreshPolicy.Trigger t, Weather w, String key, GeoLocation loc, long now) {
        return WeatherRefreshPolicy.shouldRefresh(t, w, key, loc, now);
    }

    @Test
    public void neverWithoutACity() {
        for (WeatherRefreshPolicy.Trigger t : WeatherRefreshPolicy.Trigger.values()) {
            assertFalse(due(t, null, null, null, T0));
            assertFalse(due(t, W, A.key(), null, T0 + 10 * WeatherRefreshPolicy.HOURLY_MS));
        }
    }

    @Test
    public void missingOrForeignSnapshotIsAlwaysFetched() {
        for (WeatherRefreshPolicy.Trigger t : WeatherRefreshPolicy.Trigger.values()) {
            assertTrue(due(t, null, null, A, T0));
            assertTrue(due(t, W, null, A, T0));
            assertTrue(due(t, W, A.key(), B, T0));
        }
    }

    @Test
    public void activationNeedsTenMinutesOfAge() {
        long min = WeatherRefreshPolicy.ACTIVATION_MIN_AGE_MS;
        assertFalse(due(WeatherRefreshPolicy.Trigger.ACTIVATED, W, A.key(), A, T0));
        assertFalse(due(WeatherRefreshPolicy.Trigger.ACTIVATED, W, A.key(), A, T0 + min - 1));
        assertTrue(due(WeatherRefreshPolicy.Trigger.ACTIVATED, W, A.key(), A, T0 + min));
    }

    @Test
    public void hourlyNeedsSixtyMinutesOfAge() {
        long hour = WeatherRefreshPolicy.HOURLY_MS;
        assertFalse(due(WeatherRefreshPolicy.Trigger.HOURLY, W, A.key(), A, T0 + 30 * 60 * 1000L));
        assertFalse(due(WeatherRefreshPolicy.Trigger.HOURLY, W, A.key(), A, T0 + hour - 1));
        assertTrue(due(WeatherRefreshPolicy.Trigger.HOURLY, W, A.key(), A, T0 + hour));
    }
}
