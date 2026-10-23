package com.alexmak.atvlauncher;

/** Weather icon kinds; the Android drawable for each is chosen in {@code WeatherIcons}. */
enum WeatherIcon {
    SUN, MOON, CLOUD_SUN, CLOUD_MOON, CLOUD, CLOUD_FOG, CLOUD_DRIZZLE, CLOUD_RAIN,
    CLOUD_RAIN_WIND, CLOUD_HAIL, CLOUD_SNOW, CLOUD_SUN_RAIN, CLOUD_MOON_RAIN,
    CLOUD_LIGHTNING, CLOUD_OFF;

    /**
     * MET Norway symbol_code (base[_day|_night|_polartwilight]) to icon; unknown gives CLOUD_OFF.
     * The suffix is stripped; {@code isDay} picks sun vs moon variants. Order matters: thunder first.
     */
    static WeatherIcon fromMet(String symbolCode, boolean isDay) {
        String base = symbolCode;
        int us = base.indexOf('_');
        if (us >= 0) base = base.substring(0, us);
        if (base.contains("thunder")) return CLOUD_LIGHTNING;
        if (base.contains("rainshowers")) return isDay ? CLOUD_SUN_RAIN : CLOUD_MOON_RAIN;
        if (base.contains("sleet")) return CLOUD_HAIL;
        if (base.contains("snow")) return CLOUD_SNOW;
        switch (base) {
            case "clearsky": case "fair": return isDay ? SUN : MOON;
            case "partlycloudy": return isDay ? CLOUD_SUN : CLOUD_MOON;
            case "cloudy": return CLOUD;
            case "fog": return CLOUD_FOG;
            case "lightrain": return CLOUD_DRIZZLE;
            case "rain": return CLOUD_RAIN;
            case "heavyrain": return CLOUD_RAIN_WIND;
            default: return CLOUD_OFF;
        }
    }
}
