package com.alexmak.atvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.TimeZone;

/** Persists the resolved location and the last weather snapshot so the UI can show them instantly. */
final class WeatherPrefs {
    private static final String FILE = "weather";
    /** Key prefix of the cached snapshot; bumped when the data source changed (v1 = Open-Meteo, v2 = before the phenomenon word; older ignored). */
    private static final String W = "w3_";
    /** Key prefix of the saved location; bumped when the timezone was added (g1 = unprefixed lat/lon/city/country, ignored). */
    private static final String G = "g2_";
    private final SharedPreferences prefs;

    WeatherPrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Null when no location has been saved yet. */
    GeoLocation location() {
        if (!prefs.contains(G + "lat") || !prefs.contains(G + "lon") || !prefs.contains(G + "country")
                || !prefs.contains(G + "tz")) {
            return null;
        }
        return new GeoLocation(Double.longBitsToDouble(prefs.getLong(G + "lat", 0)),
                Double.longBitsToDouble(prefs.getLong(G + "lon", 0)), prefs.getString(G + "city", ""),
                prefs.getString(G + "country", ""), prefs.getString(G + "tz", ""));
    }

    /** Timezone of the saved city; the device's own while no city is set. */
    TimeZone zone() {
        GeoLocation g = location();
        return g == null ? TimeZone.getDefault() : TimeZone.getTimeZone(g.timezoneId);
    }

    void saveLocation(GeoLocation g) {
        prefs.edit()
                .putLong(G + "lat", Double.doubleToRawLongBits(g.lat))
                .putLong(G + "lon", Double.doubleToRawLongBits(g.lon))
                .putString(G + "city", g.cityRu)
                .putString(G + "country", g.country)
                .putString(G + "tz", g.timezoneId)
                .apply();
    }

    /** Null when nothing cached. */
    Weather weather() {
        if (!prefs.contains(W + "fetched")) return null;
        return new Weather(Double.longBitsToDouble(prefs.getLong(W + "temp", 0)),
                icon(prefs.getString(W + "icon", "")), prefs.getBoolean(W + "day", true), prefs.getString(W + "phenomenon", ""),
                Double.longBitsToDouble(prefs.getLong(W + "max", 0)),
                Double.longBitsToDouble(prefs.getLong(W + "min", 0)),
                prefs.getLong(W + "fetched", 0));
    }

    /** {@link GeoLocation#key} of the city the cached snapshot was fetched for; null when nothing cached. */
    String weatherLocationKey() {
        return prefs.getString(W + "location", null);
    }

    /** @param locationKey {@link GeoLocation#key} of the city {@code w} was fetched for */
    void saveWeather(Weather w, String locationKey) {
        prefs.edit()
                .putString(W + "location", locationKey)
                .putLong(W + "temp", Double.doubleToRawLongBits(w.tempC))
                .putString(W + "icon", w.icon.name())
                .putBoolean(W + "day", w.isDay)
                .putString(W + "phenomenon", w.phenomenon)
                .putLong(W + "max", Double.doubleToRawLongBits(w.maxC))
                .putLong(W + "min", Double.doubleToRawLongBits(w.minC))
                .putLong(W + "fetched", w.fetchedAtMillis)
                .apply();
    }

    /** Null-safe enum decode: an unknown stored name shows the "no data" icon. */
    private static WeatherIcon icon(String name) {
        for (WeatherIcon i : WeatherIcon.values()) if (i.name().equals(name)) return i;
        return WeatherIcon.CLOUD_OFF;
    }
}
