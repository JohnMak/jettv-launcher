package com.johnmak.atvlauncher;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable IP-derived location plus parser for ipwho.is. */
final class GeoLocation {
    static final String URL = "https://ipwho.is/?lang=ru";

    final double lat;
    final double lon;
    final String cityRu;
    /** ISO 3166-1 alpha-2 from ipwho.is {@code country_code}, upper case ("RU"). */
    final String country;
    /** IANA timezone id of the place ("Asia/Novosibirsk"); always a zone the platform knows. */
    final String timezoneId;

    GeoLocation(double lat, double lon, String cityRu, String country, String timezoneId) {
        this.lat = lat;
        this.lon = lon;
        this.cityRu = cityRu;
        this.country = country;
        this.timezoneId = timezoneId;
    }

    /** Identity of the place for caching: coordinates. */
    String key() {
        return lat + "," + lon;
    }

    static FetchResult<GeoLocation> parse(String json) {
        if (json == null || json.isEmpty()) return FetchResult.err("empty geo response");
        try {
            JSONObject o = new JSONObject(json);
            if (!o.optBoolean("success", false)) {
                return FetchResult.err("ipwho.is: " + o.optString("message", "failure"));
            }
            double lat = o.getDouble("latitude");
            double lon = o.getDouble("longitude");
            String city = o.optString("city", "");
            String country = o.getString("country_code");
            String timezone = o.getJSONObject("timezone").getString("id");
            if (!TimezoneLabel.isKnown(timezone)) return FetchResult.err("unknown timezone: " + timezone);
            return FetchResult.ok(new GeoLocation(lat, lon, city, country, timezone));
        } catch (JSONException e) {
            return FetchResult.err("bad geo response: " + e.getMessage());
        }
    }
}
