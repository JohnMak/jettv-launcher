package com.johnmak.atvlauncher;

import java.util.regex.Pattern;

/** The description shown under a feed card: the app's own text, cut to the first sentences. */
final class FeedDescription {
    private static final int SENTENCES = 3;
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0]+");

    private FeedDescription() {}

    /** The long description when there is one, else the short one; cut to the first {@value #SENTENCES} sentences. */
    static String of(String longDescription, String shortDescription) {
        String text = collapse(longDescription);
        if (text.isEmpty()) text = collapse(shortDescription);
        return firstSentences(text, SENTENCES);
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
