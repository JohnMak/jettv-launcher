package com.johnmak.atvlauncher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The only place art content may be fetched from: HTTPS on {@code art.jmwork.ru}. Pure, mirrors {@link UpdateHosts}.
 * Holds the single host reference in code: every art URL is derived from {@link #ART_BASE_URL}, and the published
 * manifest's {@code base_url} must equal it exactly (checked in {@link ArtManifest}). The manifest cannot redirect
 * the client anywhere else.
 */
final class ArtHosts {
    static final String HOST = "art.jmwork.ru";
    static final String ART_BASE_URL = "https://art.jmwork.ru/";
    static final String ART_MANIFEST_URL = ART_BASE_URL + "manifest.json";
    private static final String IMAGES_PREFIX = ART_BASE_URL + "images/";

    /**
     * One safe path segment: starts with an alphanumeric, then up to 99 of {@code [A-Za-z0-9._-]}, ending {@code .jpg}.
     * No {@code /}, no {@code ..}, so an entry's {@code file} can never escape the {@code images/} directory or point
     * off-host. Matches the published layout {@code images/<id>-<sha1[0:12]>.jpg}.
     */
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.jpg");

    private ArtHosts() {}

    /** True when {@code file} is one safe path segment ending {@code .jpg} (see {@link #SAFE_FILE}). */
    static boolean isSafeFile(String file) {
        return file != null && SAFE_FILE.matcher(file).matches();
    }

    /** Absolute image URL for a manifest {@code file} name; err when the name is unsafe. */
    static FetchResult<String> imageUrl(String file) {
        if (!isSafeFile(file)) return FetchResult.err("unsafe file name: " + file);
        return FetchResult.ok(IMAGES_PREFIX + file);
    }

    /** ok(url) when it is https, host exactly {@code art.jmwork.ru}, default/443 port, without credentials. */
    static FetchResult<String> check(String url) {
        if (url == null) return FetchResult.err("url is null");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return FetchResult.err("bad url: " + url);
        }
        if (!"https".equals(uri.getScheme())) return FetchResult.err("not https: " + url);
        String host = uri.getHost();
        if (host == null || !HOST.equals(host.toLowerCase(Locale.ROOT))) return FetchResult.err("host not allowed: " + url);
        if (uri.getUserInfo() != null) return FetchResult.err("credentials in url: " + url);
        if (uri.getPort() != -1 && uri.getPort() != 443) return FetchResult.err("port not allowed: " + url);
        return FetchResult.ok(url);
    }

    /** The absolute target of a redirect {@code location} from {@code current}, checked like any other url. */
    static FetchResult<String> redirect(String current, String location) {
        if (location == null || location.isEmpty()) return FetchResult.err("redirect without Location");
        try {
            return check(new URI(current).resolve(new URI(location)).toString());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return FetchResult.err("bad redirect: " + location);
        }
    }
}
