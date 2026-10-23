package com.johnmak.atvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses the GitHub "latest release" JSON into a {@link Release}. Accepts only a published, non-prerelease release
 * whose tag is vX.Y.Z and which carries exactly the assets jettv-launcher-X.Y.Z.apk and .apk.sha256 on allowed hosts.
 */
final class ReleaseFeed {
    static final String LATEST_URL = "https://api.github.com/repos/johnmak/jettv-launcher/releases/latest";

    /** Where the newest release comes from; blocking, worker thread only. Errors carry {@link UpdateError}. */
    interface Source {
        FetchResult<Release> latest();
    }

    /** Production source: the GitHub API. */
    static Source github(String userAgent) {
        return () -> {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", userAgent);
            headers.put("Accept", "application/vnd.github+json");
            FetchResult<String> body = HttpText.get(LATEST_URL, headers);
            if (!body.isOk()) return UpdateError.fail(UpdateError.NETWORK, body.error());
            return parse(body.value());
        };
    }

    private ReleaseFeed() {}

    static FetchResult<Release> parse(String json) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException | NullPointerException e) {
            return invalid("not a JSON object");
        }
        if (root.optBoolean("draft", false)) return invalid("draft release");
        if (root.optBoolean("prerelease", false)) return invalid("prerelease");
        String tag = root.optString("tag_name", "");
        if (!tag.startsWith("v")) return invalid("tag is not vX.Y.Z: '" + tag + "'");
        FetchResult<SemVer> version = SemVer.parse(tag.substring(1));
        if (!version.isOk()) return invalid(version.error());
        JSONArray assets = root.optJSONArray("assets");
        if (assets == null) return invalid("no assets");
        String apkName = Release.apkAssetName(version.value());
        FetchResult<String> apk = assetUrl(assets, apkName);
        if (!apk.isOk()) return apk.propagate();
        FetchResult<String> sha = assetUrl(assets, apkName + ".sha256");
        if (!sha.isOk()) return sha.propagate();
        return FetchResult.ok(new Release(version.value(), apk.value(), sha.value()));
    }

    /** The download url of the one asset called {@code name}; missing, duplicate or off-host is a feed error. */
    private static FetchResult<String> assetUrl(JSONArray assets, String name) {
        String url = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name", ""))) continue;
            if (url != null) return invalid("duplicate asset " + name);
            url = asset.optString("browser_download_url", "");
        }
        if (url == null) return invalid("missing asset " + name);
        FetchResult<String> checked = UpdateHosts.check(url);
        if (!checked.isOk()) return invalid(name + ": " + checked.error());
        return checked;
    }

    private static <T> FetchResult<T> invalid(String message) {
        return UpdateError.fail(UpdateError.FEED_INVALID, message);
    }
}
