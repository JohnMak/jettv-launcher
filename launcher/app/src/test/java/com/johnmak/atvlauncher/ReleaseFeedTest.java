package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class ReleaseFeedTest {
    private static FetchResult<Release> parse(String fixture) throws IOException {
        try (InputStream in = ReleaseFeedTest.class.getResourceAsStream("/release/" + fixture)) {
            if (in == null) throw new AssertionError("missing fixture " + fixture);
            return ReleaseFeed.parse(new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void assertFeedInvalid(FetchResult<Release> r) {
        assertFalse(r.isOk());
        assertEquals(UpdateError.FEED_INVALID, UpdateError.of(r));
    }

    @Test public void acceptsPublishedRelease() throws IOException {
        FetchResult<Release> r = parse("ok.json");
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertEquals("1.2.3", r.value().version.toString());
        assertEquals("https://github.com/johnmak/jettv-launcher/releases/download/v1.2.3/jettv-launcher-1.2.3.apk", r.value().apkUrl);
        assertEquals(r.value().apkUrl + ".sha256", r.value().sha256Url);
        assertNull(UpdateError.of(r));
    }

    @Test public void rejectsPrereleaseAndDraft() throws IOException {
        assertFeedInvalid(parse("prerelease.json"));
        assertFeedInvalid(parse("draft.json"));
    }

    @Test public void rejectsMissingAsset() throws IOException {
        assertFeedInvalid(parse("missing_asset.json"));
    }

    @Test public void rejectsAssetsNotMatchingTheTag() throws IOException {
        assertFeedInvalid(parse("tag_mismatch.json"));
    }

    @Test public void rejectsForeignHostAndPlainHttp() throws IOException {
        assertFeedInvalid(parse("bad_host.json"));
        assertFeedInvalid(parse("http_url.json"));
    }

    @Test public void rejectsBadTagAndGarbage() throws IOException {
        assertFeedInvalid(parse("bad_tag.json"));
        assertFeedInvalid(ReleaseFeed.parse("not json"));
        assertFeedInvalid(ReleaseFeed.parse("{}"));
        assertFeedInvalid(ReleaseFeed.parse("[]"));
    }

    @Test public void hostsAllowGithubAndItsCdnOnly() {
        assertTrue(UpdateHosts.check("https://objects.githubusercontent.com/x?y=1").isOk());
        assertTrue(UpdateHosts.check("https://release-assets.githubusercontent.com/x").isOk());
        assertFalse(UpdateHosts.check("https://github.com.evil.com/x").isOk());
        assertFalse(UpdateHosts.check("https://evilgithub.com/x").isOk());
        assertFalse(UpdateHosts.check("https://user@github.com/x").isOk());
        assertFalse(UpdateHosts.check("https://github.com:8443/x").isOk());
        assertFalse(UpdateHosts.check("ftp://github.com/x").isOk());
        assertFalse(UpdateHosts.check("not a url").isOk());
    }

    @Test public void redirectsAreResolvedAndChecked() {
        String from = "https://github.com/a/b/releases/download/v1/x.apk";
        assertEquals("https://objects.githubusercontent.com/z",
                UpdateHosts.redirect(from, "https://objects.githubusercontent.com/z").value());
        assertEquals("https://github.com/other", UpdateHosts.redirect(from, "/other").value());
        assertFalse(UpdateHosts.redirect(from, "https://evil.com/z").isOk());
        assertFalse(UpdateHosts.redirect(from, "http://github.com/z").isOk());
        assertFalse(UpdateHosts.redirect(from, null).isOk());
        assertFalse(UpdateHosts.redirect(from, "").isOk());
    }
}
