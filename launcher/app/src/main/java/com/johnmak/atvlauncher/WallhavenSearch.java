package com.johnmak.atvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The pure, bounded parser of a Wallhaven {@code GET /api/v1/search} JSON response — the structural trust perimeter
 * that REPLACES the retired private manifest's (lost with that manifest; see plan B3). Never touches disk, the network,
 * or shared state. It turns raw public bytes into a {@link Page} of {@link WallhavenImage} descriptors plus the
 * {@code meta.seed} and {@code meta.last_page} the streaming pager needs (plan B5).
 *
 * <p>Perimeter (every untrusted field is bounded, as the retired manifest parser's 256KiB/500-entry caps were):
 * <ul>
 *   <li>a {@link #JSON_MAX_BYTES} byte cap on the whole response (the fetch is capped at the same figure);</li>
 *   <li>an entry-count cap {@link #MAX_ENTRIES} (Wallhaven serves 24/page; capped defensively higher);</li>
 *   <li>per-string length caps on every extracted field ({@code path}, {@code id}, {@code resolution});</li>
 *   <li>pagination sanity — {@code current_page}/{@code last_page} coerced non-negative and capped at
 *       {@link #MAX_PAGE};</li>
 *   <li>each descriptor's {@code path} must itself pass {@link WallhavenHosts#check} (https + {@code w.wallhaven.cc});
 *       a malformed or off-allowlist entry is SKIPPED at the boundary (as the retired manifest parser skipped bad
 *       entries), never aborting the whole page.</li>
 * </ul>
 *
 * <p>A page that parses but yields zero usable descriptors is a VALID EMPTY page (not an error) — the pager advances
 * to the next page (plan B5 valid-empty-page). {@link OutOfMemoryError} from the parse itself is caught and surfaced
 * as an error (plan B4 phase-4), never a crash.
 */
final class WallhavenSearch {
    /** Byte cap on the search response document; the fetch is capped at the same figure. */
    static final long JSON_MAX_BYTES = 512L * 1024;
    /** A parsed page with more raw {@code data} elements than this is rejected (Wallhaven serves 24/page). */
    static final int MAX_ENTRIES = 64;
    static final int MAX_PATH_LEN = 512;
    static final int MAX_ID_LEN = 64;
    static final int MAX_RESOLUTION_LEN = 32;
    /** Pagination sanity ceiling — a {@code current_page}/{@code last_page} beyond this is clamped. */
    static final int MAX_PAGE = 100_000;

    private WallhavenSearch() {}

    /** One parsed search page: the usable descriptors (possibly empty), the random {@code seed} that produced them,
     * and the pagination bounds. {@code seed} may be empty (page 1 with no carried seed). */
    static final class Page {
        final List<WallhavenImage> images;
        final String seed;
        final int page;      // meta.current_page, clamped to [0, MAX_PAGE]
        final int lastPage;  // meta.last_page, clamped to [0, MAX_PAGE]

        Page(List<WallhavenImage> images, String seed, int page, int lastPage) {
            this.images = Collections.unmodifiableList(images);
            this.seed = seed;
            this.page = page;
            this.lastPage = lastPage;
        }
    }

    /**
     * Parses and structurally validates a search response. ok(page) when the bytes are within {@link #JSON_MAX_BYTES},
     * the document is a JSON object, and the {@code data} array is present with at most {@link #MAX_ENTRIES} elements
     * (an empty array is a valid empty page). Malformed entries are skipped. err otherwise — the caller keeps its
     * current window untouched and treats it as a page-fetch/parse failure.
     */
    static FetchResult<Page> parse(byte[] bytes) {
        if (bytes == null) return FetchResult.err("no search bytes");
        if (bytes.length > JSON_MAX_BYTES) return FetchResult.err("search over cap: " + bytes.length);
        try {
            JSONObject root;
            try {
                root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException | NullPointerException e) {
                return FetchResult.err("not a JSON object");
            }
            JSONArray data = root.optJSONArray("data");
            if (data == null) return FetchResult.err("no data array");
            if (data.length() > MAX_ENTRIES) return FetchResult.err("too many entries: " + data.length());

            List<WallhavenImage> images = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                WallhavenImage image = imageOf(data.optJSONObject(i));
                if (image != null) images.add(image);
            }

            JSONObject meta = root.optJSONObject("meta");
            String seed = meta == null ? "" : clamp(meta.optString("seed", ""), MAX_ID_LEN);
            int page = meta == null ? 0 : clampPage(meta.optInt("current_page", 0));
            int lastPage = meta == null ? 0 : clampPage(meta.optInt("last_page", 0));
            return FetchResult.ok(new Page(images, seed, page, lastPage));
        } catch (OutOfMemoryError oom) {
            return FetchResult.err("search parse oom");
        }
    }

    /** One descriptor, or null when it is malformed / off-allowlist (skipped at the boundary). */
    private static WallhavenImage imageOf(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        if (id.isEmpty() || id.length() > MAX_ID_LEN) return null;
        String path = o.optString("path", "");
        if (path.isEmpty() || path.length() > MAX_PATH_LEN) return null;
        if (!WallhavenHosts.check(path).isOk()) return null; // full-res URL must be https on w.wallhaven.cc
        String resolution = clamp(o.optString("resolution", ""), MAX_RESOLUTION_LEN);
        return new WallhavenImage(id, path, resolution);
    }

    /** {@code value} truncated to at most {@code max} chars (never null). */
    private static String clamp(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** A page number coerced into {@code [0, MAX_PAGE]} (negative/absurd values sanitised). */
    private static int clampPage(int page) {
        if (page < 0) return 0;
        return Math.min(page, MAX_PAGE);
    }
}
