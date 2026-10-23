package com.johnmak.atvlauncher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The only places an update may be downloaded from: HTTPS on GitHub and its release CDN. Pure. */
final class UpdateHosts {
    static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")));

    private UpdateHosts() {}

    /** ok(url) when it is https, on an allowed host, default port, without credentials. */
    static FetchResult<String> check(String url) {
        if (url == null) return FetchResult.err("url is null");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return FetchResult.err("bad url: " + url);
        }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme())) return FetchResult.err("not https: " + url);
        if (host == null || !ALLOWED.contains(host.toLowerCase(Locale.ROOT))) return FetchResult.err("host not allowed: " + url);
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
