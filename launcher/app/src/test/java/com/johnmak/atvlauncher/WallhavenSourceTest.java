package com.johnmak.atvlauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;

public class WallhavenSourceTest {
    private static final WallhavenSource.AbortCheck NEVER = () -> null;

    // ---- searchUrl builder --------------------------------------------------------------------

    @Test public void page1NoSeedNoTagCarriesOnlyTheSfwDefaults() {
        String url = WallhavenSource.searchUrl(null, null, 1);
        assertTrue(url.startsWith("https://wallhaven.cc/api/v1/search?"));
        assertTrue("General only", url.contains("categories=100"));
        assertTrue("SFW only", url.contains("purity=100"));
        assertTrue(url.contains("atleast=3840x2160"));
        assertTrue(url.contains("ratios=16x9"));
        assertTrue(url.contains("sorting=random"));
        assertTrue(url.contains("&page=1"));
        assertFalse("no seed on the first page", url.contains("seed="));
        assertFalse("no tag when none given", url.contains("q="));
        assertTrue("stays within the allowlist", WallhavenHosts.check(url).isOk());
    }

    @Test public void laterPagesCarryTheSeedForRandomPaginationContinuity() {
        String url = WallhavenSource.searchUrl(null, "aBc123", 2);
        assertTrue(url.contains("&page=2"));
        assertTrue(url.contains("&seed=aBc123"));
        assertTrue(WallhavenHosts.check(url).isOk());
    }

    @Test public void presetTagIsAppendedAndUrlEncoded() {
        String url = WallhavenSource.searchUrl("sky landscape", "s", 1);
        assertTrue("tag appended", url.contains("&q=sky"));
        assertTrue("space is URL-encoded", url.contains("sky+landscape") || url.contains("sky%20landscape"));
        assertFalse("no raw space breaking the URL", url.contains("sky landscape"));
        assertTrue(WallhavenHosts.check(url).isOk());
    }

    @Test public void alwaysSfwGeneralNeverNsfw() {
        for (String q : new String[]{null, "", "abstract", "space"}) {
            String url = WallhavenSource.searchUrl(q, "s", 3);
            assertTrue(url.contains("categories=100"));
            assertTrue(url.contains("purity=100"));
            assertFalse(url.contains("purity=111") || url.contains("purity=010"));
        }
    }

    @Test public void nonPositivePageCoercedToOne() {
        assertTrue(WallhavenSource.searchUrl(null, null, 0).contains("&page=1"));
        assertTrue(WallhavenSource.searchUrl(null, null, -3).contains("&page=1"));
    }

    // ---- readBounded: Content-Length boundaries -----------------------------------------------

    @Test public void readBoundedPresentLengthExactMatch() throws IOException {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), body.length, 1024, NEVER);
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertEquals(5, r.value().bytesRead);
        assertArrayEquals(body, Arrays.copyOf(r.value().buffer, r.value().bytesRead));
    }

    @Test public void readBoundedPresentLengthTruncatedBodyFails() throws IOException {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8); // advertised 5, only 3 arrive
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), 5, 1024, NEVER);
        assertFalse("truncated body does not match advertised length", r.isOk());
        assertTrue(r.error().contains("truncated"));
    }

    @Test public void readBoundedPresentLengthOversizedBodyFails() throws IOException {
        byte[] body = "abcdefg".getBytes(StandardCharsets.UTF_8); // advertised 5, 7 arrive
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), 5, 1024, NEVER);
        assertFalse("more bytes than advertised is rejected", r.isOk());
        assertTrue(r.error().contains("oversized"));
    }

    @Test public void readBoundedLengthOverCapRejectedBeforeAlloc() throws IOException {
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(new byte[0]), 2000, 1024, NEVER);
        assertFalse(r.isOk());
        assertTrue(r.error().contains("too large"));
    }

    // ---- readBounded: absent Content-Length ---------------------------------------------------

    @Test public void readBoundedAbsentLengthUnderCap() throws IOException {
        byte[] body = new byte[10];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), -1, 1024, NEVER);
        assertTrue(r.isOk());
        assertEquals("exact valid length travels with the oversized buffer", 10, r.value().bytesRead);
        assertArrayEquals(body, Arrays.copyOf(r.value().buffer, r.value().bytesRead));
    }

    @Test public void readBoundedAbsentLengthExactlyCapIsOk() throws IOException {
        byte[] body = new byte[16];
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), -1, 16, NEVER);
        assertTrue("filling the cap exactly, then EOF, is valid", r.isOk());
        assertEquals(16, r.value().bytesRead);
    }

    @Test public void readBoundedAbsentLengthCapPlusOneOverflows() throws IOException {
        byte[] body = new byte[17]; // cap is 16 — filling the buffer alone does NOT prove the body fit
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), -1, 16, NEVER);
        assertFalse("a body exceeding the cap must be rejected, never silently truncated", r.isOk());
        assertTrue(r.error().contains("oversized"));
    }

    @Test public void readBoundedHonoursTheAbortCheck() throws IOException {
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(new byte[100]), -1, 1024, () -> "cancelled");
        assertFalse(r.isOk());
        assertEquals("cancelled", r.error());
    }

    @Test public void readBoundedRechecksAbortRightBeforeReportingSuccessPresentLength() throws IOException {
        // A well-formed exact-length body, but a cancel lands during the post-body EOF probe: success must NOT win.
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        WallhavenSource.AbortCheck cancelAtEnd = cancelAfter(2); // nulls while reading; "cancelled" on the final probe
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), body.length, 1024, cancelAtEnd);
        assertFalse("a cancellation during the EOF probe must not be reported as a successful fetch", r.isOk());
        assertEquals("cancelled", r.error());
    }

    @Test public void readBoundedRechecksAbortRightBeforeReportingSuccessAbsentLength() throws IOException {
        byte[] body = new byte[5];
        WallhavenSource.AbortCheck cancelAtEnd = cancelAfter(3); // absent-length has one extra post-loop poll
        FetchResult<WallhavenSource.Handle> r = WallhavenSource.readBounded(
                new ByteArrayInputStream(body), -1, 1024, cancelAtEnd);
        assertFalse(r.isOk());
        assertEquals("cancelled", r.error());
    }

    /** An abort check that returns null for the first {@code nullPolls} calls, then "cancelled" forever after. */
    private static WallhavenSource.AbortCheck cancelAfter(int nullPolls) {
        int[] polls = {0};
        return () -> (++polls[0] > nullPolls) ? "cancelled" : null;
    }

    // ---- Content-Type + Retry-After pure helpers ----------------------------------------------

    @Test public void isImageContentTypeAcceptsImageRejectsOthers() {
        assertTrue(WallhavenSource.isImageContentType("image/jpeg"));
        assertTrue(WallhavenSource.isImageContentType("IMAGE/PNG"));
        assertTrue(WallhavenSource.isImageContentType("image/jpeg; charset=binary"));
        assertFalse(WallhavenSource.isImageContentType("text/html"));
        assertFalse(WallhavenSource.isImageContentType("application/json"));
        assertFalse(WallhavenSource.isImageContentType(null));
    }

    @Test public void parseRetryAfterSecondsToMillis() {
        assertEquals(30_000L, WallhavenSource.parseRetryAfterMs("30"));
        assertEquals(0L, WallhavenSource.parseRetryAfterMs("0"));
        assertEquals("HTTP-date form is not a plain integer → default backoff", -1L,
                WallhavenSource.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(-1L, WallhavenSource.parseRetryAfterMs(null));
        assertEquals(-1L, WallhavenSource.parseRetryAfterMs("-5"));
    }

    // ---- loop-level integration (fake connection) ---------------------------------------------

    @Test public void searchFetchesAndParsesAPage() {
        String json = "{\"data\":[{\"id\":\"a\",\"path\":\"https://w.wallhaven.cc/full/a/a.jpg\","
                + "\"resolution\":\"3840x2160\"}],\"meta\":{\"current_page\":1,\"last_page\":5,\"seed\":\"sd\"}}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        WallhavenSource src = source(new Net(FakeConn.ok(body, body.length, "application/json")));
        FetchResult<WallhavenSearch.Page> r = src.search(null, null, 1);
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertEquals(1, r.value().images.size());
        assertEquals("sd", r.value().seed);
        assertEquals(5, r.value().lastPage);
    }

    @Test public void imageFetchReturnsBoundedHandleForAnImageContentType() {
        byte[] body = new byte[]{1, 2, 3, 4};
        WallhavenSource src = source(new Net(FakeConn.ok(body, body.length, "image/jpeg")));
        FetchResult<WallhavenSource.Handle> r = src.image(img());
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertEquals(4, r.value().bytesRead);
    }

    @Test public void nonImageContentTypeIsRejectedEarly() {
        WallhavenSource src = source(new Net(FakeConn.ok("<html/>".getBytes(StandardCharsets.UTF_8), 7, "text/html")));
        FetchResult<WallhavenSource.Handle> r = src.image(img());
        assertFalse(r.isOk());
        assertTrue(r.error().contains("not an image"));
    }

    @Test public void http429SurfacesStructuredStatusWithRetryAfter() {
        WallhavenSource src = source(new Net(FakeConn.status(429, "45")));
        FetchResult<WallhavenSource.Handle> r = src.image(img());
        assertFalse(r.isOk());
        WallhavenSource.HttpStatus st = r.errorKind(WallhavenSource.HttpStatus.class);
        assertNotNull("429 carries a structured status, not just a string", st);
        assertEquals(429, st.code);
        assertEquals(45_000L, st.retryAfterMs);
    }

    @Test public void http503SurfacesStructuredStatusWithoutRetryAfter() {
        WallhavenSource src = source(new Net(FakeConn.status(503, null)));
        FetchResult<WallhavenSource.Handle> r = src.image(img());
        assertFalse(r.isOk());
        WallhavenSource.HttpStatus st = r.errorKind(WallhavenSource.HttpStatus.class);
        assertNotNull(st);
        assertEquals(503, st.code);
        assertEquals(-1L, st.retryAfterMs);
    }

    @Test public void offAllowlistRedirectIsRefused() {
        WallhavenSource src = source(new Net(FakeConn.redirect(302, "https://th.wallhaven.cc/small/x.jpg")));
        FetchResult<WallhavenSource.Handle> r = src.image(img());
        assertFalse("a redirect to the thumbnail host (off the two-host allowlist) is refused", r.isOk());
    }

    // ---- fakes ---------------------------------------------------------------------------------

    private static WallhavenImage img() {
        return new WallhavenImage("a", "https://w.wallhaven.cc/full/a/wallhaven-a.jpg", "3840x2160");
    }

    private static WallhavenSource source(Net net) {
        return new WallhavenSource("ua-test", net, () -> 0L, (delayMs, onFire) -> () -> { });
    }

    private static final class Net implements WallhavenSource.Connections {
        final ConcurrentLinkedQueue<FakeConn> queue = new ConcurrentLinkedQueue<>();

        Net(FakeConn... conns) {
            Collections.addAll(queue, conns);
        }

        @Override public HttpURLConnection open(String url) throws IOException {
            FakeConn c = queue.poll();
            if (c == null) throw new IOException("no scripted connection for " + url);
            return c;
        }
    }

    private static final class FakeConn extends HttpURLConnection {
        int status = 200;
        String location;
        String contentType;
        String retryAfter;
        long contentLength = -1;
        byte[] body = new byte[0];

        FakeConn() throws IOException {
            super(new URL("https://wallhaven.cc/placeholder"));
        }

        static FakeConn ok(byte[] body, long contentLength, String contentType) {
            try {
                FakeConn c = new FakeConn();
                c.status = 200;
                c.body = body;
                c.contentLength = contentLength;
                c.contentType = contentType;
                return c;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        static FakeConn redirect(int code, String location) {
            try {
                FakeConn c = new FakeConn();
                c.status = code;
                c.location = location;
                return c;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        static FakeConn status(int code, String retryAfter) {
            try {
                FakeConn c = new FakeConn();
                c.status = code;
                c.retryAfter = retryAfter;
                return c;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override public int getResponseCode() {
            return status;
        }

        @Override public String getContentType() {
            return contentType;
        }

        @Override public String getHeaderField(String name) {
            if ("Location".equalsIgnoreCase(name)) return location;
            if ("Retry-After".equalsIgnoreCase(name)) return retryAfter;
            return null;
        }

        @Override public long getContentLengthLong() {
            return contentLength;
        }

        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }

        @Override public void setRequestProperty(String key, String value) { }

        @Override public void connect() { }

        @Override public void disconnect() { }

        @Override public boolean usingProxy() {
            return false;
        }
    }
}
