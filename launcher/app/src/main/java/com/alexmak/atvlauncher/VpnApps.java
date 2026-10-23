package com.alexmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure rules of the VPN top-bar button: which apps are VPN apps, which one is used, what OK does. */
final class VpnApps {
    static final String BIND_VPN_SERVICE = "android.permission.BIND_VPN_SERVICE";

    /** A service found for the VpnService action. */
    static final class Candidate {
        final String packageName;
        final String label;
        final String servicePermission;

        Candidate(String packageName, String label, String servicePermission) {
            this.packageName = packageName;
            this.label = label;
            this.servicePermission = servicePermission;
        }
    }

    static final class App {
        final String packageName;
        final String label;

        App(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    enum Action { OPEN_APP, TOGGLE_OFF }

    private VpnApps() {}

    /** VPN apps = packages with a service guarded by BIND_VPN_SERVICE; one entry per package, sorted by label. */
    static List<App> filter(List<Candidate> candidates) {
        List<App> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : candidates) {
            if (BIND_VPN_SERVICE.equals(c.servicePermission) && seen.add(c.packageName)) {
                apps.add(new App(c.packageName, c.label));
            }
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    /** The package in use: the stored one if installed, else the configured default if installed, else the first; "" = none. */
    static String choose(String stored, List<App> installed, String configDefault) {
        if (contains(installed, stored)) return stored;
        if (contains(installed, configDefault)) return configDefault;
        return installed.isEmpty() ? "" : installed.get(0).packageName;
    }

    /** Next ({@code step} 1) or previous (-1) installed package, wrapping; an unknown current one gives the first. */
    static String cycle(String current, List<App> installed, int step) {
        if (installed.isEmpty()) return "";
        int at = -1;
        for (int i = 0; i < installed.size(); i++) if (installed.get(i).packageName.equals(current)) at = i;
        if (at < 0) return installed.get(0).packageName;
        return installed.get((at + step + installed.size()) % installed.size()).packageName;
    }

    static String labelOf(String packageName, List<App> installed) {
        for (App a : installed) if (a.packageName.equals(packageName)) return a.label;
        return packageName;
    }

    /** Component («package/class») that switches the VPN off silently for a package, from parallel config arrays; "" = none. */
    static String toggleOffComponent(String packageName, String[] packages, String[] components) {
        if (packages.length != components.length) throw new IllegalArgumentException("parallel arrays differ in length");
        for (int i = 0; i < packages.length; i++) if (packages[i].equals(packageName)) return components[i];
        return "";
    }

    /** OK on the button: a connected VPN with a toggle-off component is switched off silently, everything else opens the app. */
    static Action decide(boolean vpnOn, boolean hasToggleOff) {
        return vpnOn && hasToggleOff ? Action.TOGGLE_OFF : Action.OPEN_APP;
    }

    private static boolean contains(List<App> installed, String packageName) {
        for (App a : installed) if (a.packageName.equals(packageName)) return true;
        return false;
    }
}
