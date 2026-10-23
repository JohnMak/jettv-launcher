package com.alexmak.atvlauncher;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure rules for finding a feed title among Kinopoisk search candidates: title normalisation, the kind of content
 * (movie / series / episode), type and year filters, and the pick of the best exact match.
 */
final class TitleMatch {
    /** What the feed says the card is. */
    enum Kind { UNKNOWN, MOVIE, SERIES, EPISODE }

    /** One search hit: names, Kinopoisk type string (FILM, TV_SERIES, MINI_SERIES, TV_SHOW, VIDEO), year (0 unknown), votes. */
    static final class Candidate {
        final long id;
        final String nameRu;
        final String nameEn;
        final String type;
        final int year;
        final int votes;

        Candidate(long id, String nameRu, String nameEn, String type, int year, int votes) {
            this.id = id;
            this.nameRu = nameRu;
            this.nameEn = nameEn;
            this.type = type;
            this.year = year;
            this.votes = votes;
        }
    }

    // TvContract.TYPE_* values (platform constants, mirrored so this class stays plain Java)
    private static final int TYPE_MOVIE = 0;
    private static final int TYPE_TV_SERIES = 1;
    private static final int TYPE_TV_SEASON = 2;
    private static final int TYPE_TV_EPISODE = 3;

    // No UNICODE_CHARACTER_CLASS: Android 11 (API 30) rejects it ("Unsupported flags: 256"); ICU \p{L}/\s/\d are Unicode-aware there anyway.
    private static final int U = Pattern.UNICODE_CASE;
    private static final Pattern YEAR_IN_PARENS = Pattern.compile("\\(\\s*(?:19|20)\\d\\d\\s*\\)", U);
    private static final Pattern SERIAL_WORD = Pattern.compile("(?<![\\p{L}\\p{N}])(?:сериал|serial)(?![\\p{L}\\p{N}])", U);
    private static final Pattern SEASON_SUFFIX = Pattern.compile(
            "(?:[:,\\-\\u2013\\u2014]\\s*)?(?<![\\p{L}\\p{N}])(?:(?:сезон|season)\\s*\\d+|\\d+\\s*(?:-?й\\s*)?(?:сезон|season))(?![\\p{L}\\p{N}])",
            U);
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+", U);

    private TitleMatch() {}

    /**
     * Lower case, "(2019)", the word «сериал» and season suffixes removed, punctuation turned into single spaces.
     * Keeps «ё»: this is what is sent to the search.
     */
    static String clean(String title) {
        String s = title == null ? "" : title.toLowerCase(Locale.ROOT);
        s = YEAR_IN_PARENS.matcher(s).replaceAll(" ");
        s = SEASON_SUFFIX.matcher(s).replaceAll(" ");
        s = SERIAL_WORD.matcher(s).replaceAll(" ");
        return NOT_WORD.matcher(s).replaceAll(" ").trim();
    }

    /** {@link #clean} with «ё» folded into «е»: the form titles are compared in. */
    static String normalize(String title) {
        return clean(title).replace('ё', 'е');
    }

    static Kind kindOf(String season, String episode, int tvType) {
        if (tvType == TYPE_TV_EPISODE || tvType == TYPE_TV_SEASON || !season.isEmpty() || !episode.isEmpty()) {
            return Kind.EPISODE;
        }
        if (tvType == TYPE_TV_SERIES) return Kind.SERIES;
        if (tvType == TYPE_MOVIE) return Kind.MOVIE;
        return Kind.UNKNOWN;
    }

    /** Cache key of a lookup; a title that normalises to nothing, or an unknown year (no lookup is made), has no key (empty). */
    static String key(String title, int year, Kind kind) {
        String name = normalize(title);
        if (name.isEmpty() || year <= 0) return "";
        return name + "|" + year + "|" + kind;
    }

    /**
     * The candidate whose Russian or original name equals the title, of the right type and with exactly the
     * release year (series: start year); an unknown year on either side is no match. Most votes wins, search order
     * breaks ties.
     */
    static Optional<Candidate> pick(String title, int year, Kind kind, List<Candidate> candidates) {
        String wanted = normalize(title);
        if (wanted.isEmpty()) return Optional.empty();
        Candidate best = null;
        for (Candidate c : candidates) {
            if (!wanted.equals(normalize(c.nameRu)) && !wanted.equals(normalize(c.nameEn))) continue;
            if (!typeFits(kind, c.type)) continue;
            if (year <= 0 || c.year != year) continue;
            if (best == null || c.votes > best.votes) best = c;
        }
        return Optional.ofNullable(best);
    }

    private static boolean typeFits(Kind kind, String type) {
        boolean series = "TV_SERIES".equals(type) || "MINI_SERIES".equals(type) || "TV_SHOW".equals(type);
        switch (kind) {
            case MOVIE:
                return !series;
            case SERIES:
            case EPISODE:
                return series;
            default:
                return true;
        }
    }
}
