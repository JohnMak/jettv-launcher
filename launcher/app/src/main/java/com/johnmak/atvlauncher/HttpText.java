package com.johnmak.atvlauncher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Blocking HTTPS GET returning the body text; call on a worker thread. */
final class HttpText {
    /** Identifying User-Agent with a project URL as contact (MET Norway requires one); used by every HTTP client. */
    static final String USER_AGENT = "JetTV-Launcher/" + BuildConfig.VERSION_NAME + " (+https://github.com/johnmak/jettv-launcher)";
    /** The error of a 429 response, so callers can back off. */
    static final String RATE_LIMITED = "HTTP 429";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 256 * 1024;

    private HttpText() {}

    static FetchResult<String> get(String url) {
        return get(url, Collections.emptyMap());
    }

    /** GET with extra request headers (e.g. an API token). */
    static FetchResult<String> get(String url, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int status = c.getResponseCode();
            if (status != 200) return FetchResult.err("HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) return FetchResult.err("response too large");
                }
                return FetchResult.ok(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (IOException | RuntimeException e) {
            return FetchResult.err("network: " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
