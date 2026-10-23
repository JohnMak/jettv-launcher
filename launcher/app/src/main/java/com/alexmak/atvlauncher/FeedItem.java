package com.alexmak.atvlauncher;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One card of the content feed (a preview or watch-next program), already cleaned for display. */
final class FeedItem {
    static final int NO_TYPE = -1;
    private static final float MIN_CARD_ASPECT = 0.8f;
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})(?:\\D.*)?$");

    final String packageName;
    final String title;
    /** Poster URI (https, http or content); null when the program has none. */
    final String posterUri;
    final String intentUri;
    /** TvContract POSTER_ART_ASPECT_RATIO_* constant. */
    final int posterAspect;
    final long channelId;
    final int weight;
    final long engagementMillis;
    /** Review rating on the 10-point scale; empty when the program has none or it is not readable. */
    final Optional<Rating> rating;
    /** TvContract season / episode display numbers, trimmed; empty for movies. */
    final String season;
    final String episode;
    /** Release year from COLUMN_RELEASE_DATE ("2019" or "2019-05-30"); 0 when absent or unreadable. */
    final int year;
    /** TvContract COLUMN_TYPE (TYPE_MOVIE, TYPE_TV_SERIES, ...); {@link #NO_TYPE} when the program has none. */
    final int type;

    FeedItem(String packageName, String title, String posterUri, String intentUri, int posterAspect,
             long channelId, int weight, long engagementMillis, String reviewRating, int reviewRatingStyle,
             String season, String episode, String releaseDate, int type) {
        this.packageName = packageName;
        this.title = clean(title);
        this.posterUri = posterUri;
        this.intentUri = intentUri;
        this.posterAspect = posterAspect;
        this.channelId = channelId;
        this.weight = weight;
        this.engagementMillis = engagementMillis;
        this.rating = Rating.parse(reviewRating, reviewRatingStyle);
        this.season = clean(season);
        this.episode = clean(episode);
        this.year = parseYear(releaseDate);
        this.type = type;
    }

    /** Card width / height: the poster ratio, kept between a slim poster and widescreen. */
    float cardAspect() {
        float ratio;
        switch (posterAspect) {
            case 1: ratio = 3f / 2f; break; // TvContract.ASPECT_RATIO_3_2
            case 2: ratio = 4f / 3f; break; // ASPECT_RATIO_4_3
            case 3: ratio = 1f; break;      // ASPECT_RATIO_1_1
            case 4: ratio = 2f / 3f; break; // ASPECT_RATIO_2_3
            default: ratio = 16f / 9f;      // ASPECT_RATIO_16_9 and anything unknown
        }
        return Math.max(MIN_CARD_ASPECT, ratio);
    }

    /** The year of a release date string; 0 when it does not start with four digits. */
    static int parseYear(String releaseDate) {
        if (releaseDate == null) return 0;
        Matcher m = YEAR.matcher(releaseDate.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Collapses all whitespace runs (newlines included) into single spaces; null becomes empty. */
    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
