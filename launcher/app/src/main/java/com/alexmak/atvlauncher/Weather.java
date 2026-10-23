package com.alexmak.atvlauncher;

/** Immutable weather snapshot. */
final class Weather {
    final double tempC;
    final WeatherIcon icon;
    final boolean isDay;
    /** Precipitation / phenomenon word ("дождь", "снег", "гроза"...); empty for clear or cloudy. */
    final String phenomenon;
    final double maxC;
    final double minC;
    final long fetchedAtMillis;

    Weather(double tempC, WeatherIcon icon, boolean isDay, String phenomenon, double maxC, double minC, long fetchedAtMillis) {
        this.tempC = tempC;
        this.icon = icon;
        this.isDay = isDay;
        this.phenomenon = phenomenon;
        this.maxC = maxC;
        this.minC = minC;
        this.fetchedAtMillis = fetchedAtMillis;
    }

    /** "+5°", "−3°" (U+2212), "0°". */
    static String formatTemp(double c) {
        long r = Math.round(c);
        if (r == 0) return "0°";
        return (r > 0 ? "+" : "−") + Math.abs(r) + "°";
    }

    String tempText() {
        return formatTemp(tempC);
    }

    /** "+4° / −1°": day maximum / night minimum. */
    String rangeText() {
        return formatTemp(maxC) + " / " + formatTemp(minC);
    }
}
