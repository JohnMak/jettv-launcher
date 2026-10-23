package com.johnmak.atvlauncher;

/** Android side of {@link WeatherIcon}: the one place that maps each kind to its drawable. */
final class WeatherIcons {
    private WeatherIcons() {
    }

    static int drawable(WeatherIcon icon) {
        switch (icon) {
            case SUN: return R.drawable.ic_sun;
            case MOON: return R.drawable.ic_moon;
            case CLOUD_SUN: return R.drawable.ic_cloud_sun;
            case CLOUD_MOON: return R.drawable.ic_cloud_moon;
            case CLOUD: return R.drawable.ic_cloud;
            case CLOUD_FOG: return R.drawable.ic_cloud_fog;
            case CLOUD_DRIZZLE: return R.drawable.ic_cloud_drizzle;
            case CLOUD_RAIN: return R.drawable.ic_cloud_rain;
            case CLOUD_RAIN_WIND: return R.drawable.ic_cloud_rain_wind;
            case CLOUD_HAIL: return R.drawable.ic_cloud_hail;
            case CLOUD_SNOW: return R.drawable.ic_cloud_snow;
            case CLOUD_SUN_RAIN: return R.drawable.ic_cloud_sun_rain;
            case CLOUD_MOON_RAIN: return R.drawable.ic_cloud_moon_rain;
            case CLOUD_LIGHTNING: return R.drawable.ic_cloud_lightning;
            case CLOUD_OFF: return R.drawable.ic_cloud_off;
            default: throw new IllegalArgumentException("unmapped icon " + icon);
        }
    }
}
