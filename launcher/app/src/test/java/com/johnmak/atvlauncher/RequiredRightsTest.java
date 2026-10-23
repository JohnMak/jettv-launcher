package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class RequiredRightsTest {
    static final class FakeProbe implements RequiredRights.Probe {
        final Set<ShellCommand.GrantablePermission> granted = new HashSet<>();
        boolean usage;
        boolean home;

        @Override public boolean hasPermission(ShellCommand.GrantablePermission p) { return granted.contains(p); }
        @Override public boolean usageStatsAllowed() { return usage; }
        @Override public boolean holdsHome() { return home; }
    }

    @Test public void everythingMissingListsAllFiveInOrder() {
        assertEquals(Arrays.asList(RequiredRights.Right.FINE_LOCATION, RequiredRights.Right.TV_LISTINGS,
                RequiredRights.Right.SECURE_SETTINGS, RequiredRights.Right.USAGE_STATS, RequiredRights.Right.HOME),
                RequiredRights.missing(new FakeProbe()));
    }

    @Test public void grantedRightsDisappearAndHomeIsNotAGrant() {
        FakeProbe p = new FakeProbe();
        p.granted.add(ShellCommand.GrantablePermission.ACCESS_FINE_LOCATION);
        p.usage = true;
        List<RequiredRights.Right> missing = RequiredRights.missing(p);
        assertEquals(Arrays.asList(RequiredRights.Right.TV_LISTINGS, RequiredRights.Right.SECURE_SETTINGS,
                RequiredRights.Right.HOME), missing);
        assertEquals(Arrays.asList(RequiredRights.Right.TV_LISTINGS, RequiredRights.Right.SECURE_SETTINGS),
                RequiredRights.withoutHome(missing));
    }

    @Test public void allSatisfiedIsEmpty() {
        FakeProbe p = new FakeProbe();
        p.granted.addAll(Arrays.asList(ShellCommand.GrantablePermission.values()));
        p.usage = true;
        p.home = true;
        assertTrue(RequiredRights.missing(p).isEmpty());
        assertEquals(Collections.emptyList(), RequiredRights.withoutHome(RequiredRights.missing(p)));
    }
}
