package com.alexmak.atvlauncher;

import java.util.List;

/** City search by name; synchronous, call off the main thread. */
final class GeocodingClient {
    private GeocodingClient() {}

    static FetchResult<List<GeocodingModel.Place>> search(String query) {
        FetchResult<String> body = HttpText.get(GeocodingModel.url(query));
        if (!body.isOk()) return FetchResult.err(body.error());
        return GeocodingModel.parse(body.value());
    }
}
