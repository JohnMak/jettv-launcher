package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class RecentAppsTest {
    private static final int PAUSED = 2;
    private static final Set<String> LAUNCHABLE = new HashSet<>(Arrays.asList("a", "b", "c", "own"));

    private static RecentApps.Event resumed(String pkg, long t) {
        return new RecentApps.Event(pkg, RecentApps.RESUMED, t);
    }

    @Test
    public void mostRecentFirstEachPackageOnce() {
        List<String> r = RecentApps.opened(Arrays.asList(
                resumed("a", 100), resumed("b", 200), resumed("a", 300), resumed("c", 250)),
                0, LAUNCHABLE, "own");
        assertEquals(Arrays.asList("a", "c", "b"), r);
    }

    @Test
    public void onlyResumeEventsAfterBootCount() {
        List<RecentApps.Event> ev = Arrays.asList(
                resumed("a", 50), resumed("b", 150), new RecentApps.Event("c", PAUSED, 400), resumed("a", 120));
        assertEquals(Arrays.asList("b", "a"), RecentApps.opened(ev, 100, LAUNCHABLE, "own"));
        assertEquals(Collections.singletonList("b"), RecentApps.opened(ev, 120, LAUNCHABLE, "own"));
        assertEquals(Collections.emptyList(), RecentApps.opened(ev, 150, LAUNCHABLE, "own"));
    }

    @Test
    public void ownAndNonLaunchablePackagesAreIgnored() {
        List<RecentApps.Event> ev = Arrays.asList(resumed("own", 10), resumed("system.ui", 20), resumed("a", 5));
        assertEquals(Collections.singletonList("a"), RecentApps.opened(ev, 0, LAUNCHABLE, "own"));
    }

    @Test
    public void noEventsNoApps() {
        assertEquals(Collections.emptyList(), RecentApps.opened(Collections.emptyList(), 0, LAUNCHABLE, "own"));
    }
}
