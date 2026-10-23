package com.alexmak.atvlauncher;

import java.util.TimeZone;

/** The weather source: MET Norway locationforecast, no key. Synchronous, call off the main thread. */
final class MetProvider {
    private MetProvider() {}

    static FetchResult<Weather> fetch(GeoLocation location) {
        FetchResult<String> body = HttpText.get(WeatherModel.url(location.lat, location.lon));
        if (!body.isOk()) return FetchResult.err(body.error());
        return WeatherModel.parse(body.value(), System.currentTimeMillis(), TimeZone.getDefault());
    }
}
