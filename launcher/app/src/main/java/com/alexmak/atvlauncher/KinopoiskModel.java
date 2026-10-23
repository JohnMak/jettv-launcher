package com.alexmak.atvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * kinopoiskapiunofficial.tech request URLs and response parsers (search-by-keyword v2.1, film v2.2).
 * No exceptions escape the parsers.
 */
final class KinopoiskModel {
    private static final String BASE = "https://kinopoiskapiunofficial.tech/api/";

    private KinopoiskModel() {}

    static String searchUrl(String keyword) {
        try {
            return BASE + "v2.1/films/search-by-keyword?page=1&keyword=" + URLEncoder.encode(keyword, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always supported
        }
    }

    static String filmUrl(long id) {
        return BASE + "v2.2/films/" + id;
    }

    /** films[] to candidates; a film without an id is skipped, missing or "null" numbers are 0. */
    static FetchResult<List<TitleMatch.Candidate>> parseSearch(String json) {
        if (json == null || json.isEmpty()) return FetchResult.err("empty kinopoisk search response");
        try {
            JSONArray films = new JSONObject(json).getJSONArray("films");
            List<TitleMatch.Candidate> out = new ArrayList<>();
            for (int i = 0; i < films.length(); i++) {
                JSONObject f = films.getJSONObject(i);
                long id = f.optLong("filmId", 0);
                if (id <= 0) continue;
                out.add(new TitleMatch.Candidate(id, text(f, "nameRu"), text(f, "nameEn"), text(f, "type"),
                        number(f.opt("year")), number(f.opt("ratingVoteCount"))));
            }
            return FetchResult.ok(out);
        } catch (JSONException e) {
            return FetchResult.err("kinopoisk search: " + e.getMessage());
        }
    }

    /**
     * Ratings (one decimal, empty when absent or not positive) of one film, only when its year equals {@code year}
     * exactly; any other film year gives {@link KinopoiskInfo#NONE}.
     */
    static FetchResult<KinopoiskInfo> parseFilm(String json, int year) {
        if (json == null || json.isEmpty()) return FetchResult.err("empty kinopoisk film response");
        try {
            JSONObject f = new JSONObject(json);
            if (f.optLong("kinopoiskId", 0) <= 0) return FetchResult.err("kinopoisk film: no kinopoiskId");
            if (year <= 0 || number(f.opt("year")) != year) return FetchResult.ok(KinopoiskInfo.NONE);
            return FetchResult.ok(new KinopoiskInfo(rating(f.opt("ratingKinopoisk")), rating(f.opt("ratingImdb"))));
        } catch (JSONException e) {
            return FetchResult.err("kinopoisk film: " + e.getMessage());
        }
    }

    /** A string field; JSON null and absent are empty. */
    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    /** Integer from a JSON number or numeric string ("2008"); anything else ("null", absent) is 0. */
    private static int number(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String rating(Object v) {
        double d;
        if (v instanceof Number) d = ((Number) v).doubleValue();
        else return "";
        return d > 0 ? String.format(Locale.ROOT, "%.1f", d) : "";
    }
}
