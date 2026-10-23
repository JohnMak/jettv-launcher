package com.johnmak.atvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * MET Norway locationforecast/2.0/compact request URL and response parser (data CC BY 4.0).
 * No exceptions escape parse().
 */
final class WeatherModel {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int DAY_START_HOUR = 7;
    private static final int DAY_END_HOUR = 19;

    private WeatherModel() {}

    /** MET asks for at most 4 decimals in coordinates. */
    static String url(double lat, double lon) {
        return String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon);
    }

    /**
     * Current temperature and symbol come from timeseries[0]. The symbol is taken from next_1_hours;
     * the documented schema drops that bucket near the end of the series (and may for the first
     * entry in some regions), where next_6_hours is the only symbol present, so it is used then.
     * Day maximum / night minimum: air_temperature over entries within 24 h of the first one,
     * day = local {@code tz} hour in [7,19), night = the rest.
     */
    static FetchResult<Weather> parse(String json, long fetchedAtMillis, TimeZone tz) {
        if (json == null || json.isEmpty()) return FetchResult.err("empty weather response");
        try {
            JSONArray series = new JSONObject(json).getJSONObject("properties").getJSONArray("timeseries");
            if (series.length() == 0) return FetchResult.err("met.no: empty timeseries");
            JSONObject first = series.getJSONObject(0);
            JSONObject data = first.getJSONObject("data");
            double temp = airTemperature(data);
            String symbol = symbol(data);
            long t0 = parseTime(first.getString("time"));
            boolean isDay = isDay(symbol, localHour(t0, tz));

            double max = Double.NEGATIVE_INFINITY;
            double min = Double.POSITIVE_INFINITY;
            for (int i = 0; i < series.length(); i++) {
                JSONObject e = series.getJSONObject(i);
                long t = parseTime(e.getString("time"));
                if (t - t0 >= DAY_MS) break;
                double v = airTemperature(e.getJSONObject("data"));
                int h = localHour(t, tz);
                if (h >= DAY_START_HOUR && h < DAY_END_HOUR) max = Math.max(max, v);
                else min = Math.min(min, v);
            }
            if (max == Double.NEGATIVE_INFINITY || min == Double.POSITIVE_INFINITY) {
                return FetchResult.err("met.no: timeseries too short for day/night range");
            }
            return FetchResult.ok(new Weather(temp, WeatherIcon.fromMet(symbol, isDay), isDay, phenomenon(symbol), max, min,
                    fetchedAtMillis));
        } catch (JSONException | ParseException e) {
            return FetchResult.err("bad weather response: " + e.getMessage());
        }
    }

    /**
     * Symbols with a _day/_night/_polartwilight suffix carry it (polartwilight counts as day);
     * suffix-free symbols (cloudy, fog, rain...) use the local hour in [7,19).
     */
    static boolean isDay(String symbolCode, int localHour) {
        if (symbolCode.endsWith("_day") || symbolCode.endsWith("_polartwilight")) return true;
        if (symbolCode.endsWith("_night")) return false;
        return localHour >= DAY_START_HOUR && localHour < DAY_END_HOUR;
    }

    /**
     * Russian precipitation word of a MET symbol code (day/night suffix ignored); empty for clear / cloudy.
     * Precedence: thunder, sleet, snow, showers (ливень), heavy rain (ливень), rain, fog.
     */
    static String phenomenon(String symbolCode) {
        if (symbolCode.contains("thunder")) return "гроза";
        if (symbolCode.contains("sleet")) return "мокрый снег";
        if (symbolCode.contains("snow")) return "снег";
        if (symbolCode.contains("rainshowers") || symbolCode.startsWith("heavyrain")) return "ливень";
        if (symbolCode.contains("rain")) return "дождь";
        if (symbolCode.startsWith("fog")) return "туман";
        return "";
    }

    private static double airTemperature(JSONObject data) throws JSONException {
        return data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
    }

    private static String symbol(JSONObject data) throws JSONException {
        JSONObject bucket = data.has("next_1_hours") ? data.getJSONObject("next_1_hours")
                : data.getJSONObject("next_6_hours");
        return bucket.getJSONObject("summary").getString("symbol_code");
    }

    private static long parseTime(String iso) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date d = f.parse(iso);
        if (d == null) throw new ParseException("bad time " + iso, 0);
        return d.getTime();
    }

    private static int localHour(long millis, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(millis);
        return c.get(Calendar.HOUR_OF_DAY);
    }
}
