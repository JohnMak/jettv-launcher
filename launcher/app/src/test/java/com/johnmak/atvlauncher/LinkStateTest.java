package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LinkStateTest {
    @Test
    public void wifiLevelSelectsIconAndKeepsSsid() {
        LinkState.Icon[] expected = {LinkState.Icon.WIFI_ZERO, LinkState.Icon.WIFI_LOW, LinkState.Icon.WIFI_HIGH,
                LinkState.Icon.WIFI_HIGH, LinkState.Icon.WIFI_FULL};
        for (int level = 0; level <= LinkState.MAX_LEVEL; level++) {
            LinkState s = LinkState.resolve(false, true, level, "Home");
            assertEquals(expected[level], s.icon);
            assertEquals("Home", s.ssid);
        }
    }

    @Test
    public void outOfRangeLevelsAreClamped() {
        assertEquals(LinkState.Icon.WIFI_ZERO, LinkState.resolve(false, true, -5, null).icon);
        assertEquals(LinkState.Icon.WIFI_FULL, LinkState.resolve(false, true, 9, null).icon);
        assertNull(LinkState.resolve(false, true, 2, null).ssid);
    }

    @Test
    public void ethernetBeatsWifiAndNoLinkIsOffline() {
        assertEquals(LinkState.Icon.ETHERNET, LinkState.resolve(true, true, 4, "Home").icon);
        assertNull(LinkState.resolve(true, true, 4, "Home").ssid);
        assertEquals(LinkState.Icon.OFFLINE, LinkState.resolve(false, false, 3, "Home").icon);
        assertEquals(LinkState.Icon.OFFLINE, LinkState.offline().icon);
    }
}
