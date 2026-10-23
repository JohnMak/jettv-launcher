package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class UsageRankingTest {
    private static String[] app(String pkg, String label) {
        return new String[]{pkg, label};
    }

    private static List<String> sorted(Map<String, Long> usage, String[]... apps) {
        List<String[]> list = new ArrayList<>(Arrays.asList(apps));
        list.sort(UsageRanking.comparator(a -> a[0], a -> a[1], usage));
        List<String> out = new ArrayList<>();
        for (String[] a : list) out.add(a[0]);
        return out;
    }

    @Test
    public void moreForegroundTimeFirst() {
        Map<String, Long> usage = new HashMap<>();
        usage.put("p1", 10L);
        usage.put("p2", 500L);
        usage.put("p3", 50L);
        assertEquals(Arrays.asList("p2", "p3", "p1"),
                sorted(usage, app("p1", "A"), app("p2", "B"), app("p3", "C")));
    }

    @Test
    public void tiesAndUnusedGoByLabelCaseInsensitive() {
        Map<String, Long> usage = new HashMap<>();
        usage.put("p1", 100L);
        usage.put("p2", 100L);
        usage.put("p3", 0L);
        assertEquals(Arrays.asList("p2", "p1", "p4", "p5", "p3"),
                sorted(usage, app("p1", "Zeta"), app("p2", "alpha"), app("p3", "Beta"),
                        app("p4", "apple"), app("p5", "Banana")));
    }
}
