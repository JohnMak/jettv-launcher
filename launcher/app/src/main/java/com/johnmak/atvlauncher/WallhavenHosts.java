package com.johnmak.atvlauncher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The strict two-host allowlist for the Wallhaven image stream — the successor to {@link ArtHosts}, generalized from
 * the single {@code art.jmwork.ru} host to exactly {@code {wallhaven.cc (search API), w.wallhaven.cc (full-res CDN)}}.
 * Pure, mirrors {@link ArtHosts#check}/{@link ArtHosts#redirect}: HTTPS only, EXACT host match against the two-host
 * set, default/443 port, no credentials, the initial URL checked, and every redirect hop re-checked against the SAME
 * set (no off-allowlist redirect ever followed). {@code th.wallhaven.cc} (thumbnails) and every other host are rejected.
 *
 * <p>No NSFW surface ever: the search builder ({@link WallhavenSource#searchUrl}) hard-codes {@code categories=100}
 * (General only) + {@code purity=100} (SFW only); this class only governs WHICH hosts may be reached.
 */
final class WallhavenHosts {
    /** The search API host; the base every search URL is derived from. */
    static final String API_HOST = "wallhaven.cc";
    static final String API_BASE = "https://wallhaven.cc";
    /** The full-resolution image CDN host ({@code data[].path} in a search response points here). */
    static final String CDN_HOST = "w.wallhaven.cc";

    private WallhavenHosts() {}

    /** True when {@code host} (already lower-cased) is one of the two allowed hosts — EXACT match, no subdomain slack. */
    private static boolean allowedHost(String host) {
        return API_HOST.equals(host) || CDN_HOST.equals(host);
    }

    /**
     * ok(url) when it is https, the host is EXACTLY {@code wallhaven.cc} or {@code w.wallhaven.cc}, on the default/443
     * port, without credentials. err otherwise — identical shape to {@link ArtHosts#check} but over the two-host set.
     */
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
        if (host == null || !allowedHost(host.toLowerCase(Locale.ROOT))) {
            return FetchResult.err("host not allowed: " + url);
        }
        if (uri.getUserInfo() != null) return FetchResult.err("credentials in url: " + url);
        if (uri.getPort() != -1 && uri.getPort() != 443) return FetchResult.err("port not allowed: " + url);
        return FetchResult.ok(url);
    }

    /** The absolute target of a redirect {@code location} from {@code current}, checked like any other url (every hop). */
    static FetchResult<String> redirect(String current, String location) {
        if (location == null || location.isEmpty()) return FetchResult.err("redirect without Location");
        try {
            return check(new URI(current).resolve(new URI(location)).toString());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return FetchResult.err("bad redirect: " + location);
        }
    }
}
