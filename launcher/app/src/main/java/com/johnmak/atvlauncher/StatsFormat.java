package com.johnmak.atvlauncher;

/** Short Russian figures for the cleanup list: bytes as «312 МБ», milliseconds as «2 мин»; unknown is «—». */
final class StatsFormat {
    static final String UNKNOWN = "—";

    private static final long KB = 1024L;
    private static final long MB = KB * 1024;
    private static final long GB = MB * 1024;

    private StatsFormat() {}

    static String bytes(Long bytes) {
        if (bytes == null || bytes < 0) return UNKNOWN;
        if (bytes < MB) return Math.round(bytes / (double) KB) + " КБ";
        if (bytes < GB) return Math.round(bytes / (double) MB) + " МБ";
        long tenths = Math.round(bytes * 10 / (double) GB);
        return tenths / 10 + "," + tenths % 10 + " ГБ";
    }

    static String kilobytes(Long kb) {
        return kb == null || kb < 0 ? UNKNOWN : bytes(kb * KB);
    }

    static String millis(Long ms) {
        if (ms == null || ms < 0) return UNKNOWN;
        long sec = ms / 1000;
        if (sec < 60) return sec + " с";
        long min = sec / 60;
        if (min < 60) return min + " мин";
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? h + " ч" : h + " ч " + m + " мин";
    }
}
