package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArtHostsTest {
    @Test public void acceptsCanonicalHttpsUrls() {
        assertTrue(ArtHosts.check("https://art.jmwork.ru/manifest.json").isOk());
        assertTrue(ArtHosts.check("https://art.jmwork.ru/images/1-abc.jpg").isOk());
        assertTrue(ArtHosts.check("https://art.jmwork.ru:443/manifest.json").isOk());
        assertEquals(ArtHosts.ART_BASE_URL + "manifest.json", ArtHosts.ART_MANIFEST_URL);
    }

    @Test public void rejectsSchemeHostPortCredentials() {
        assertFalse(ArtHosts.check("http://art.jmwork.ru/x").isOk());       // not https
        assertFalse(ArtHosts.check("https://evil.com/x").isOk());           // wrong host
        assertFalse(ArtHosts.check("https://art.jmwork.ru.evil.com/x").isOk());
        assertFalse(ArtHosts.check("https://sub.art.jmwork.ru/x").isOk());  // host must be exact
        assertFalse(ArtHosts.check("https://art.jmwork.ru:8443/x").isOk()); // non-default port
        assertFalse(ArtHosts.check("https://user:pw@art.jmwork.ru/x").isOk());
        assertFalse(ArtHosts.check("not a url").isOk());
        assertFalse(ArtHosts.check(null).isOk());
    }

    @Test public void redirectResolvesAndRechecksEveryHop() {
        // same-host relative/absolute redirects pass
        assertTrue(ArtHosts.redirect("https://art.jmwork.ru/a/b", "/images/x.jpg").isOk());
        assertTrue(ArtHosts.redirect("https://art.jmwork.ru/a", "https://art.jmwork.ru/c").isOk());
        // off-host redirect target is refused even from an allowed origin
        assertFalse(ArtHosts.redirect("https://art.jmwork.ru/a", "https://evil.com/c").isOk());
        assertFalse(ArtHosts.redirect("https://art.jmwork.ru/a", "http://art.jmwork.ru/c").isOk());
        assertFalse(ArtHosts.redirect("https://art.jmwork.ru/a", null).isOk());
        assertFalse(ArtHosts.redirect("https://art.jmwork.ru/a", "").isOk());
    }

    @Test public void safeFileAcceptsPublishedNamingRejectsTraversal() {
        assertTrue(ArtHosts.isSafeFile("193223543-abc123def456.jpg"));
        assertTrue(ArtHosts.isSafeFile("a.jpg"));
        assertFalse(ArtHosts.isSafeFile("../x.jpg"));
        assertFalse(ArtHosts.isSafeFile("a/b.jpg"));
        assertFalse(ArtHosts.isSafeFile("/x.jpg"));
        assertFalse(ArtHosts.isSafeFile(".hidden.jpg"));   // must start alphanumeric
        assertFalse(ArtHosts.isSafeFile("x.png"));          // only .jpg
        assertFalse(ArtHosts.isSafeFile("x.JPG"));
        assertFalse(ArtHosts.isSafeFile(""));
        assertFalse(ArtHosts.isSafeFile(null));
        StringBuilder tooLong = new StringBuilder("a");
        for (int i = 0; i < 101; i++) tooLong.append('a');
        assertFalse(ArtHosts.isSafeFile(tooLong + ".jpg"));
    }

    @Test public void imageUrlDerivedFromBaseGateByName() {
        assertEquals("https://art.jmwork.ru/images/a-b.jpg", ArtHosts.imageUrl("a-b.jpg").value());
        assertFalse(ArtHosts.imageUrl("../a.jpg").isOk());
        assertTrue(ArtHosts.check(ArtHosts.imageUrl("a-b.jpg").value()).isOk());
    }
}
