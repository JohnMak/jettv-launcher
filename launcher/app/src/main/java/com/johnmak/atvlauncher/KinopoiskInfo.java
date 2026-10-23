package com.johnmak.atvlauncher;

/**
 * What Kinopoisk knows about one feed title, ready for display: ratings as one-decimal text.
 * Every part may be empty; {@link #NONE} (all empty) is "not found".
 */
final class KinopoiskInfo {
    static final KinopoiskInfo NONE = new KinopoiskInfo("", "");

    /** Kinopoisk rating, e.g. "7.9"; empty when absent. */
    final String kp;
    /** IMDb rating, e.g. "8.1"; empty when absent. */
    final String imdb;

    KinopoiskInfo(String kp, String imdb) {
        this.kp = kp;
        this.imdb = imdb;
    }

    boolean isEmpty() {
        return kp.isEmpty() && imdb.isEmpty();
    }
}
