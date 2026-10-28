package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WallhavenHostsTest {
    @Test public void acceptsBothAllowlistedHostsHttpsOnly() {
        assertTrue(WallhavenHosts.check("https://wallhaven.cc/api/v1/search?page=1").isOk());
        assertTrue(WallhavenHosts.check("https://w.wallhaven.cc/full/94/wallhaven-94x38z.jpg").isOk());
        assertTrue(WallhavenHosts.check("https://wallhaven.cc:443/api/v1/search").isOk());
        assertTrue(WallhavenHosts.check("https://w.wallhaven.cc:443/full/x.jpg").isOk());
    }

    @Test public void rejectsSchemeHostPortCredentials() {
        assertFalse("not https", WallhavenHosts.check("http://wallhaven.cc/api").isOk());
        assertFalse("thumbnail host is NOT allowlisted", WallhavenHosts.check("https://th.wallhaven.cc/small/x.jpg").isOk());
        assertFalse("arbitrary host", WallhavenHosts.check("https://evil.com/x").isOk());
        assertFalse("suffix spoof", WallhavenHosts.check("https://wallhaven.cc.evil.com/x").isOk());
        assertFalse("prefix spoof", WallhavenHosts.check("https://evilwallhaven.cc/x").isOk());
        assertFalse("sub of api host", WallhavenHosts.check("https://a.wallhaven.cc/x").isOk());
        assertFalse("non-default port", WallhavenHosts.check("https://wallhaven.cc:8443/x").isOk());
        assertFalse("credentials", WallhavenHosts.check("https://user:pw@wallhaven.cc/x").isOk());
        assertFalse(WallhavenHosts.check("not a url").isOk());
        assertFalse(WallhavenHosts.check(null).isOk());
    }

    @Test public void redirectResolvesAndRechecksEveryHopWithinTheTwoHostSet() {
        // a cross-host hop WITHIN the allowlist (api -> cdn) is allowed, re-checked
        assertTrue(WallhavenHosts.redirect("https://wallhaven.cc/api/v1/search",
                "https://w.wallhaven.cc/full/x.jpg").isOk());
        // same-host relative redirect
        assertTrue(WallhavenHosts.redirect("https://w.wallhaven.cc/a/b", "/full/x.jpg").isOk());
        // off-allowlist redirect target refused even from an allowed origin
        assertFalse(WallhavenHosts.redirect("https://wallhaven.cc/a", "https://th.wallhaven.cc/c.jpg").isOk());
        assertFalse(WallhavenHosts.redirect("https://wallhaven.cc/a", "https://evil.com/c").isOk());
        assertFalse(WallhavenHosts.redirect("https://wallhaven.cc/a", "http://wallhaven.cc/c").isOk());
        assertFalse(WallhavenHosts.redirect("https://wallhaven.cc/a", null).isOk());
        assertFalse(WallhavenHosts.redirect("https://wallhaven.cc/a", "").isOk());
    }
}
