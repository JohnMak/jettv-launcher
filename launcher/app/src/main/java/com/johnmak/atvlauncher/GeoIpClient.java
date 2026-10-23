package com.johnmak.atvlauncher;

/** Resolves approximate location by public IP; synchronous, call off the main thread. */
final class GeoIpClient {
    private GeoIpClient() {}

    static FetchResult<GeoLocation> fetch() {
        FetchResult<String> body = HttpText.get(GeoLocation.URL);
        if (!body.isOk()) return FetchResult.err(body.error());
        return GeoLocation.parse(body.value());
    }
}
