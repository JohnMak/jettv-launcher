package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class VpnAppsTest {
    private static final String PERM = "android.permission.BIND_VPN_SERVICE";

    private static List<VpnApps.App> installed() {
        return Arrays.asList(new VpnApps.App("a.one", "Alpha"), new VpnApps.App("b.two", "Beta"),
                new VpnApps.App("c.three", "Gamma"));
    }

    @Test public void filterKeepsOnlyBindVpnServicesOncePerPackageSortedByLabel() {
        List<VpnApps.App> apps = VpnApps.filter(Arrays.asList(
                new VpnApps.Candidate("z.last", "zeta", PERM),
                new VpnApps.Candidate("no.perm", "Fake", null),
                new VpnApps.Candidate("other.perm", "Other", "android.permission.BIND_JOB_SERVICE"),
                new VpnApps.Candidate("a.first", "Alpha", PERM),
                new VpnApps.Candidate("a.first", "Alpha", PERM)));
        assertEquals(2, apps.size());
        assertEquals("a.first", apps.get(0).packageName);
        assertEquals("Alpha", apps.get(0).label);
        assertEquals("z.last", apps.get(1).packageName);
        assertTrue(VpnApps.filter(Collections.emptyList()).isEmpty());
    }

    @Test public void chooseStoredThenDefaultThenFirstThenNone() {
        assertEquals("b.two", VpnApps.choose("b.two", installed(), "c.three"));
        assertEquals("c.three", VpnApps.choose("gone.app", installed(), "c.three"));
        assertEquals("c.three", VpnApps.choose("", installed(), "c.three"));
        assertEquals("a.one", VpnApps.choose("", installed(), "not.installed"));
        assertEquals("", VpnApps.choose("b.two", Collections.emptyList(), "c.three"));
    }

    @Test public void cycleWrapsBothWaysAndStartsAtTheFirstForUnknown() {
        assertEquals("b.two", VpnApps.cycle("a.one", installed(), 1));
        assertEquals("a.one", VpnApps.cycle("c.three", installed(), 1));
        assertEquals("c.three", VpnApps.cycle("a.one", installed(), -1));
        assertEquals("a.one", VpnApps.cycle("", installed(), 1));
        assertEquals("", VpnApps.cycle("a.one", Collections.emptyList(), 1));
    }

    @Test public void labelOfFallsBackToThePackage() {
        assertEquals("Beta", VpnApps.labelOf("b.two", installed()));
        assertEquals("x.y", VpnApps.labelOf("x.y", installed()));
    }

    @Test public void toggleOffComponentComesFromTheParallelConfigArrays() {
        String[] pkgs = {"app.hiddify.com", "other.vpn"};
        String[] comps = {"app.hiddify.com/.Shortcut", "other.vpn/.Off"};
        assertEquals("other.vpn/.Off", VpnApps.toggleOffComponent("other.vpn", pkgs, comps));
        assertEquals("", VpnApps.toggleOffComponent("plain.vpn", pkgs, comps));
        try {
            VpnApps.toggleOffComponent("x", pkgs, new String[]{"one"});
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test public void okActionIsDrivenByStateAndToggleComponent() {
        assertEquals(VpnApps.Action.TOGGLE_OFF, VpnApps.decide(true, true));
        assertEquals(VpnApps.Action.OPEN_APP, VpnApps.decide(true, false));
        assertEquals(VpnApps.Action.OPEN_APP, VpnApps.decide(false, true));
        assertEquals(VpnApps.Action.OPEN_APP, VpnApps.decide(false, false));
    }
}
