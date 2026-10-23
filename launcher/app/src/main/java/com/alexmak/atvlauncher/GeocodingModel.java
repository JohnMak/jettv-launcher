package com.alexmak.atvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Open-Meteo geocoding: request URL and the pure parser of its search response. */
final class GeocodingModel {
    private static final String BASE = "https://geocoding-api.open-meteo.com/v1/search?name=";

    private GeocodingModel() {}

    /** A search hit; {@link #toLocation} gives the location to store, {@link #label} the line shown in the list. */
    static final class Place {
        final String name;
        final String admin1;
        final String country;
        final String countryCode;
        final double lat;
        final double lon;
        final String timezoneId;

        Place(String name, String admin1, String country, String countryCode, double lat, double lon, String timezoneId) {
            this.name = name;
            this.admin1 = admin1;
            this.country = country;
            this.countryCode = countryCode;
            this.lat = lat;
            this.lon = lon;
            this.timezoneId = timezoneId;
        }

        /** "Новосибирск, Новосибирская Область, Россия"; region and country are left out when absent. */
        String label() {
            StringBuilder b = new StringBuilder(name);
            if (!admin1.isEmpty()) b.append(", ").append(admin1);
            if (!country.isEmpty()) b.append(", ").append(country);
            return b.toString();
        }

        GeoLocation toLocation() {
            return new GeoLocation(lat, lon, name, countryCode, timezoneId);
        }
    }

    static String url(String query) {
        try {
            return BASE + URLEncoder.encode(query.trim(), "UTF-8") + "&count=8&language=ru&format=json";
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always present
        }
    }

    /** No matches (the service omits {@code results}) is an empty list, not an error. */
    static FetchResult<List<Place>> parse(String json) {
        if (json == null || json.isEmpty()) return FetchResult.err("empty geocoding response");
        try {
            JSONArray results = new JSONObject(json).optJSONArray("results");
            List<Place> out = new ArrayList<>();
            if (results == null) return FetchResult.ok(out);
            for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.getJSONObject(i);
                String tz = o.getString("timezone");
                if (!TimezoneLabel.isKnown(tz)) return FetchResult.err("unknown timezone: " + tz);
                out.add(new Place(o.getString("name"), o.optString("admin1", ""), o.optString("country", ""),
                        o.getString("country_code").toUpperCase(java.util.Locale.ROOT),
                        o.getDouble("latitude"), o.getDouble("longitude"), tz));
            }
            return FetchResult.ok(out);
        } catch (JSONException e) {
            return FetchResult.err("bad geocoding response: " + e.getMessage());
        }
    }
}
