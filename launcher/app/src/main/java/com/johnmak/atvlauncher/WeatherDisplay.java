package com.johnmak.atvlauncher;

/** What the weather item (and the screensaver) shows for a location and a cached snapshot. Pure. */
final class WeatherDisplay {
    static final String PROMPT = "Определить город";
    /** Separator of the screensaver line parts. */
    private static final String LINE_GAP = "   ";
    private static final String NO_CITY = "Погода";

    final WeatherIcon icon;
    /** "+4° дождь" (temperature, then the phenomenon word if any), or the city / prompt while there is no snapshot. */
    final String primary;
    /** Second line, left part: the city; empty when unknown. */
    final String city;
    /** Second line, right part: "+4° / −1°" (day max / night min); empty without a snapshot. */
    final String range;
    final boolean hasWeather;

    private WeatherDisplay(WeatherIcon icon, String primary, String city, String range, boolean hasWeather) {
        this.icon = icon;
        this.primary = primary;
        this.city = city;
        this.range = range;
        this.hasWeather = hasWeather;
    }

    /** @param location null when no city has been determined yet; @param weather null when nothing is cached */
    static WeatherDisplay of(GeoLocation location, Weather weather) {
        if (location == null) return new WeatherDisplay(WeatherIcon.CLOUD_OFF, PROMPT, "", "", false);
        String city = location.cityRu;
        if (weather == null) {
            return new WeatherDisplay(WeatherIcon.CLOUD_OFF, city.isEmpty() ? NO_CITY : city, "", "", false);
        }
        String first = weather.phenomenon.isEmpty() ? weather.tempText()
                : weather.tempText() + " " + weather.phenomenon;
        return new WeatherDisplay(weather.icon, first, city, weather.rangeText(), true);
    }

    /** Screensaver line; empty (hidden) without a snapshot. */
    String starfieldText() {
        if (!hasWeather) return "";
        return city.isEmpty() ? primary + LINE_GAP + range : primary + LINE_GAP + city + LINE_GAP + range;
    }
}
