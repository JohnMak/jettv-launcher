package com.johnmak.atvlauncher;

/** What the top bar's network item shows: Wi-Fi with a signal level, ethernet, or no link. Pure mapping, no framework calls. */
final class LinkState {
    enum Icon { WIFI_ZERO, WIFI_LOW, WIFI_HIGH, WIFI_FULL, ETHERNET, OFFLINE }

    /** Highest Wi-Fi signal level (WifiManager#getMaxSignalLevel on Android 11+ is 4). */
    static final int MAX_LEVEL = 4;

    /**
     * Monotone map from signal level (0..4) to the four Lucide glyphs: 0 dot only, 1 one arc, 2-3 two arcs, 4 all three.
     */
    private static final Icon[] WIFI_BY_LEVEL = {Icon.WIFI_ZERO, Icon.WIFI_LOW, Icon.WIFI_HIGH, Icon.WIFI_HIGH, Icon.WIFI_FULL};

    final Icon icon;
    /** Wi-Fi network name when known, else null. */
    final String ssid;

    private LinkState(Icon icon, String ssid) {
        this.icon = icon;
        this.ssid = ssid;
    }

    static LinkState offline() {
        return new LinkState(Icon.OFFLINE, null);
    }

    /** Ethernet wins over Wi-Fi (Android prefers it too); with neither the link is offline. */
    static LinkState resolve(boolean ethernetUp, boolean wifiUp, int wifiLevel, String ssid) {
        if (ethernetUp) return new LinkState(Icon.ETHERNET, null);
        if (!wifiUp) return offline();
        int level = Math.max(0, Math.min(MAX_LEVEL, wifiLevel));
        return new LinkState(WIFI_BY_LEVEL[level], ssid);
    }
}
