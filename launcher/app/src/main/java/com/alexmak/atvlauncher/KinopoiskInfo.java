package com.alexmak.atvlauncher;

import java.util.regex.Pattern;

/**
 * What Kinopoisk knows about one feed title, ready for display: ratings as one-decimal text and the description.
 * Every part may be empty; {@link #NONE} (all empty) is "not found".
 */
final class KinopoiskInfo {
    static final KinopoiskInfo NONE = new KinopoiskInfo("", "", "");

    private static final int DESCRIPTION_SENTENCES = 3;
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0]+");

    /** Kinopoisk rating, e.g. "7.9"; empty when absent. */
    final String kp;
    /** IMDb rating, e.g. "8.1"; empty when absent. */
    final String imdb;
    /** Kinopoisk short description, else the first sentences of the full one; empty when absent. */
    final String description;

    KinopoiskInfo(String kp, String imdb, String description) {
        this.kp = kp;
        this.imdb = imdb;
        this.description = description;
    }

    boolean isEmpty() {
        return kp.isEmpty() && imdb.isEmpty() && description.isEmpty();
    }

    /** The short description when there is one, else the first {@value #DESCRIPTION_SENTENCES} sentences of the full text. */
    static String describe(String shortDescription, String fullDescription) {
        String shortText = collapse(shortDescription);
        if (!shortText.isEmpty()) return shortText;
        return firstSentences(collapse(fullDescription), DESCRIPTION_SENTENCES);
    }

    /** Whitespace runs (nbsp included) become single spaces; null becomes empty. */
    static String collapse(String s) {
        return s == null ? "" : SPACES.matcher(s).replaceAll(" ").trim();
    }

    /**
     * Cuts after the n-th sentence end: '.', '!', '?' or '…' followed by a space and an uppercase letter, quote or
     * digit. A dot after a single capital letter ("Дж. Смит") is an initial, not a sentence end.
     */
    static String firstSentences(String text, int n) {
        int found = 0;
        for (int i = 0; i + 2 < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '.' && c != '!' && c != '?' && c != '…') continue;
            if (text.charAt(i + 1) != ' ' || !startsSentence(text.charAt(i + 2))) continue;
            if (c == '.' && isInitial(text, i)) continue;
            if (++found == n) return text.substring(0, i + 1);
        }
        return text;
    }

    private static boolean startsSentence(char c) {
        return Character.isUpperCase(c) || Character.isDigit(c) || c == '«' || c == '"' || c == '“';
    }

    private static boolean isInitial(String text, int dot) {
        return dot >= 1 && Character.isUpperCase(text.charAt(dot - 1)) && (dot == 1 || text.charAt(dot - 2) == ' ');
    }
}
