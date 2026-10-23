package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AppGroupingTest {
    private static String[] app(String pkg, String label) {
        return new String[]{pkg, label};
    }

    private static List<String> names(AppGrouping.Section<String[]> s) {
        List<String> out = new ArrayList<>();
        for (String[] a : s.items) out.add(a[0]);
        return out;
    }

    @Test
    public void headerAppsAreOrderedByConfigAndExcludedFromTheGrid() {
        List<String[]> apps = Arrays.asList(app("a", "A"), app("store", "Store"), app("set", "Settings"), app("b", "B"));
        List<String> order = Arrays.asList("store", "missing", "set");
        assertEquals(Arrays.asList("store", "set"), names(AppGrouping.pick(apps, x -> x[0], order)));
        assertEquals(Arrays.asList("a", "b"),
                names(AppGrouping.exclude(apps, x -> x[0], new HashSet<>(order))));
    }

    private static List<String> names(List<String[]> list) {
        List<String> out = new ArrayList<>();
        for (String[] a : list) out.add(a[0]);
        return out;
    }

    @Test
    public void sectionsFollowExplicitMapOrderedByLaunchesAndEmptyOnesAreHidden() {
        Map<String, Long> launches = new HashMap<>();
        launches.put("k", 5L);
        launches.put("l", 9L);
        List<AppGrouping.Section<String[]>> sections = AppGrouping.group(
                Arrays.asList(app("k", "Kino"), app("l", "Lampa"), app("x", "Zed"), app("y", "Alpha"), app("v", "Vlc")),
                a -> a[0], a -> a[1], launches,
                Arrays.asList("Cinemas", "Tools", "Stores", "Other"),
                Arrays.asList(new HashSet<>(Arrays.asList("k", "l")), new HashSet<>(Arrays.asList("v")),
                        new HashSet<String>()));
        assertEquals(3, sections.size()); // Stores is empty and hidden
        assertEquals("Cinemas", sections.get(0).title);
        assertEquals(Arrays.asList("l", "k"), names(sections.get(0)));
        assertEquals("Tools", sections.get(1).title);
        assertEquals("Other", sections.get(2).title);
        assertEquals(Arrays.asList("y", "x"), names(sections.get(2))); // never launched: by label
    }
}
