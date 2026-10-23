package com.johnmak.atvlauncher;

/** Pure rules of the per-row info field under a feed row: when it is visible and what the season part says. */
final class FeedInfoLine {
    private FeedInfoLine() {
    }

    /** A row's info field is visible only while focus is inside that very row. */
    static boolean visible(FocusModel.Zone field, FocusModel.Zone focused) {
        return field == focused;
    }

    /** "Сезон 2, серия 5", "Сезон 2" or "Серия 5"; empty for a movie (both numbers empty). */
    static String season(String season, String episode) {
        if (season.isEmpty() && episode.isEmpty()) return "";
        if (episode.isEmpty()) return "Сезон " + season;
        if (season.isEmpty()) return "Серия " + episode;
        return "Сезон " + season + ", серия " + episode;
    }
}
