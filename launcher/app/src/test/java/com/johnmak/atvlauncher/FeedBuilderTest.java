package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class FeedBuilderTest {
    private static FeedItem item(String pkg, String title, long channel, int weight, long engagement) {
        return new FeedItem(pkg, title, null, "intent:" + title, 0, channel, weight, engagement, null, 0, null, null, null, null, null);
    }

    private static FeedBuilder builder(Set<String> allowed, Map<String, Long> launches) {
        return new FeedBuilder(allowed, Collections.emptySet(), Collections.emptySet(), launches);
    }

    private static List<String> titles(List<FeedItem> items) {
        List<String> out = new ArrayList<>();
        for (FeedItem i : items) out.add(i.title);
        return out;
    }

    private static Set<String> pkgs(String... p) {
        return new HashSet<>(Arrays.asList(p));
    }

    private static Map<String, Long> launches(Object... pairs) {
        Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        return m;
    }

    private static Map<String, Integer> countByPackage(List<FeedItem> row) {
        Map<String, Integer> m = new HashMap<>();
        for (FeedItem i : row) m.merge(i.packageName, 1, Integer::sum);
        return m;
    }

    private static void fill(FeedBuilder b, String pkg, int n) {
        for (int i = 0; i < n; i++) b.addPreview(item(pkg, pkg + i, 1, 0, 0));
    }

    @Test
    public void watchNextSortedByRecencyAndCapped() {
        FeedBuilder b = builder(pkgs("a", "b"), Collections.emptyMap());
        for (int i = 1; i <= 200; i++) b.addWatchNext(item(i % 2 == 0 ? "a" : "b", "w" + i, 0, 0, i));
        List<FeedItem> row = b.watchNext();
        assertEquals(FeedBuilder.WATCH_NEXT_MAX, row.size());
        assertEquals("w200", row.get(0).title);
        assertEquals("w181", row.get(19).title);
    }

    @Test
    public void watchNextWithoutEngagementTimeGoesLast() {
        FeedBuilder b = builder(pkgs("a"), Collections.emptyMap());
        b.addWatchNext(item("a", "none", 0, 0, 0));
        b.addWatchNext(item("a", "old", 0, 0, 5));
        b.addWatchNext(item("a", "new", 0, 0, 9));
        assertEquals(Arrays.asList("new", "old", "none"), titles(b.watchNext()));
    }

    @Test
    public void unknownPackagesAreSkipped() {
        FeedBuilder b = builder(pkgs("a"), Collections.emptyMap());
        b.addWatchNext(item("gone", "x", 0, 0, 5));
        b.addPreview(item("gone", "y", 0, 0, 0));
        b.addPreview(item("a", "z", 0, 0, 0));
        assertTrue(b.watchNext().isEmpty());
        assertEquals(Arrays.asList("z"), titles(b.mixed()));
    }

    @Test
    public void everyPublishingAppGetsAtLeastOneCardEvenWhenNeverOpened() {
        FeedBuilder b = builder(pkgs("a", "b", "c"), launches("a", 10));
        fill(b, "a", 50);
        fill(b, "b", 50);
        fill(b, "c", 50);
        Map<String, Integer> n = countByPackage(b.mixed());
        assertEquals(1, (int) n.get("b"));
        assertEquals(1, (int) n.get("c"));
        assertEquals(28, (int) n.get("a")); // the only app with launches takes all spare slots
    }

    @Test
    public void spareSlotsFollowLaunchCountsProportionally() {
        FeedBuilder b = builder(pkgs("kino", "lampa"), launches("kino", 30, "lampa", 10));
        fill(b, "kino", 50);
        fill(b, "lampa", 50);
        List<FeedItem> row = b.mixed();
        assertEquals(FeedBuilder.MIXED_MAX, row.size());
        Map<String, Integer> n = countByPackage(row);
        assertEquals(22, (int) n.get("kino")); // 1 + 28 * 3/4
        assertEquals(8, (int) n.get("lampa"));  // 1 + 28 * 1/4
    }

    @Test
    public void roundingUsesLargestRemainderAndAlwaysFillsTheRow() {
        // 3 apps, 27 spare slots, shares 1:1:1 -> 9 each; with 28 spare (4 apps? use 2 apps, odd spare)
        FeedBuilder b = builder(pkgs("a", "b", "c"), launches("a", 1, "b", 1, "c", 1));
        fill(b, "a", 50);
        fill(b, "b", 50);
        fill(b, "c", 50);
        List<FeedItem> row = b.mixed();
        assertEquals(30, row.size());
        Map<String, Integer> n = countByPackage(row);
        assertEquals(10, (int) n.get("a"));
        assertEquals(10, (int) n.get("b"));
        assertEquals(10, (int) n.get("c"));

        b = builder(pkgs("a", "b"), launches("a", 2, "b", 1));
        fill(b, "a", 50);
        fill(b, "b", 50);
        n = countByPackage(b.mixed());
        // spare 28: exact 18.67 / 9.33 -> floors 18 + 9, leftover 1 goes to the larger fraction (a)
        assertEquals(20, (int) n.get("a"));
        assertEquals(10, (int) n.get("b"));
    }

    @Test
    public void smallAppsAreCappedAndTheRestIsRedistributed() {
        FeedBuilder b = builder(pkgs("big", "tiny"), launches("big", 1, "tiny", 100));
        fill(b, "big", 50);
        fill(b, "tiny", 3);
        Map<String, Integer> n = countByPackage(b.mixed());
        assertEquals(3, (int) n.get("tiny"));
        assertEquals(27, (int) n.get("big"));
    }

    @Test
    public void rowStaysShortWhenOnlyNeverOpenedAppsPublish() {
        FeedBuilder b = builder(pkgs("a", "b"), Collections.emptyMap());
        fill(b, "a", 50);
        fill(b, "b", 50);
        assertEquals(2, b.mixed().size());
    }

    @Test
    public void moreAppsThanSlotsKeepsTopAppsByLaunchesTiesByPackageName() {
        Set<String> all = new HashSet<>();
        Map<String, Long> l = new HashMap<>();
        for (int i = 0; i < 35; i++) {
            String p = String.format("p%02d", i);
            all.add(p);
            l.put(p, i < 5 ? 0L : 1L); // p00..p04 never opened, the rest tie
        }
        l.put("p34", 9L);
        FeedBuilder b = builder(all, l);
        for (String p : all) fill(b, p, 2);
        List<FeedItem> row = b.mixed();
        assertEquals(30, row.size());
        Map<String, Integer> n = countByPackage(row);
        assertEquals(30, n.size());
        assertTrue(n.containsKey("p34"));
        // 29 tied/used apps fill p05..p33 (by name), then p00 is the first of the zero-usage ones
        assertTrue(n.containsKey("p05"));
        assertTrue(n.containsKey("p33"));
        assertTrue(!n.containsKey("p00"));
    }

    @Test
    public void orderInterleavesAppsByShareAndIsDeterministic() {
        FeedBuilder b = builder(pkgs("a", "b"), launches("a", 3, "b", 1));
        fill(b, "a", 50);
        fill(b, "b", 50);
        List<String> t = titles(b.mixed());
        // b has 8 cards, a has 22: b's cards must be spread out, never two in a row
        for (int i = 1; i < t.size(); i++) assertTrue(!(t.get(i).startsWith("b") && t.get(i - 1).startsWith("b")));
        FeedBuilder again = builder(pkgs("a", "b"), launches("a", 3, "b", 1));
        fill(again, "a", 50);
        fill(again, "b", 50);
        assertEquals(t, titles(again.mixed()));
    }

    @Test
    public void mostPopularProgramComesFirstWithinAnApp() {
        FeedBuilder b = builder(pkgs("a"), Collections.emptyMap());
        b.addPreview(item("a", "c2", 2, 0, 0));
        b.addPreview(item("a", "c1", 1, 0, 0));
        b.addPreview(item("a", "heavy", 3, 9, 0));
        assertEquals("heavy", titles(b.mixed()).get(0));

        b = builder(pkgs("a"), Collections.emptyMap());
        b.addPreview(item("a", "c2", 2, 0, 0));
        b.addPreview(item("a", "c1", 1, 0, 0));
        assertEquals("c1", titles(b.mixed()).get(0)); // no weights: first channel
    }

    @Test
    public void trimmingNeverLosesTheBestItemsOfAnApp() {
        FeedBuilder b = builder(pkgs("a"), launches("a", 1));
        for (int i = 0; i < 500; i++) b.addPreview(item("a", "p" + i, 500 - i, 0, 0));
        List<FeedItem> row = b.mixed();
        assertEquals("p499", row.get(0).title); // lowest channel id sorts first
        assertEquals(FeedBuilder.MIXED_MAX, row.size());
    }

    @Test
    public void titleIsCleanedOnConstruction() {
        assertEquals("Line1 Line2", new FeedItem("a", " Line1\n  Line2\r\n", null, "i", 0, 0, 0, 0, null, 0, null, null, null, null, null).title);
        assertEquals("", new FeedItem("a", null, null, "i", 0, 0, 0, 0, null, 0, null, null, null, null, null).title);
        FeedItem episode = new FeedItem("a", "t", null, "i", 0, 0, 0, 0, null, 0, " 2 ", null, null, null, null);
        assertEquals("2", episode.season);
        assertEquals("", episode.episode);
    }

    @Test
    public void cardAspectFollowsPosterRatioWithinBounds() {
        assertEquals(16f / 9f, new FeedItem("a", "t", null, "i", 0, 0, 0, 0, null, 0, null, null, null, null, null).cardAspect(), 0.001f);
        assertEquals(1f, new FeedItem("a", "t", null, "i", 3, 0, 0, 0, null, 0, null, null, null, null, null).cardAspect(), 0.001f);
        assertEquals(0.8f, new FeedItem("a", "t", null, "i", 4, 0, 0, 0, null, 0, null, null, null, null, null).cardAspect(), 0.001f);
        assertEquals(16f / 9f, new FeedItem("a", "t", null, "i", 99, 0, 0, 0, null, 0, null, null, null, null, null).cardAspect(), 0.001f);
    }

    @Test
    public void ratingIsParsedOnConstruction() {
        assertEquals("8.0", new FeedItem("a", "T", null, "i", 0, 0, 0, 0, "4.0", 0, null, null, null, null, null).rating.get().text);
        assertEquals(false, new FeedItem("a", "T", null, "i", 0, 0, 0, 0, "n/a", 0, null, null, null, null, null).rating.isPresent());
    }

    @Test
    public void releaseDateGivesTheYear() {
        assertEquals(2019, FeedItem.parseYear("2019"));
        assertEquals(2019, FeedItem.parseYear(" 2019-05-30 "));
        assertEquals(2019, FeedItem.parseYear("2019-05-30T10:00:00Z"));
        assertEquals(0, FeedItem.parseYear(null));
        assertEquals(0, FeedItem.parseYear(""));
        assertEquals(0, FeedItem.parseYear("May 2019"));
        assertEquals(0, FeedItem.parseYear("20190"));
        FeedItem item = new FeedItem("a", "t", null, "i", 0, 0, 0, 0, null, 0, null, null, "2008-01-20", null, null);
        assertEquals(2008, item.year);
        assertEquals("long wins, cut to three sentences", "Раз. Два! Три?",
                new FeedItem("a", "t", null, "i", 0, 0, 0, 0, null, 0, null, null, null, "Раз. Два! Три? Четыре.", "Коротко.").description);
        assertEquals("short is the fallback", "Коротко.",
                new FeedItem("a", "t", null, "i", 0, 0, 0, 0, null, 0, null, null, null, " ", " Коротко. ").description);
        assertEquals("", item.description);
    }

    @Test
    public void excludedAppsAreFilteredPerRowAndNewAppsAreIncluded() {
        FeedBuilder b = new FeedBuilder(pkgs("a", "b", "c"), pkgs("a"), pkgs("b"), Collections.emptyMap());
        for (String p : new String[]{"a", "b", "c"}) {
            b.addWatchNext(item(p, "w" + p, 0, 0, 1));
            b.addPreview(item(p, "p" + p, 1, 0, 0));
        }
        // «Продолжить просмотр» drops its own set only, «Рекомендации» drops its own
        assertEquals(Arrays.asList("wb", "wc"), sorted(titles(b.watchNext())));
        assertEquals(Arrays.asList("pa", "pc"), sorted(titles(b.mixed())));
        // an app absent from both sets (installed later) is shown in both
        assertTrue(titles(b.watchNext()).contains("wc"));
        assertTrue(titles(b.mixed()).contains("pc"));
    }

    @Test
    public void excludedAppsRules() {
        Set<String> none = Collections.emptySet();
        Set<String> a = ExcludedApps.toggled(none, "a");
        assertEquals(pkgs("a"), a);
        assertEquals(none, ExcludedApps.toggled(a, "a"));
        assertTrue("sets are not mutated", none.isEmpty());
        assertEquals(false, ExcludedApps.isIncluded(a, "a"));
        assertTrue(ExcludedApps.isIncluded(a, "b"));
        // a package that is no longer installed does not count
        assertEquals(1, ExcludedApps.includedCount(Arrays.asList("a", "b"), pkgs("a", "gone")));
        assertEquals(2, ExcludedApps.includedCount(Arrays.asList("a", "b"), none));
    }

    @Test
    public void publishersAreCollectedBeforeExclusionAndFullyExcludedRowStaysConfigurable() {
        FeedBuilder b = new FeedBuilder(pkgs("a", "b", "gone2"), pkgs("a", "b"), pkgs("a"), Collections.emptyMap());
        b.addWatchNext(item("a", "wa", 0, 0, 1));
        b.addWatchNext(item("b", "wb", 0, 0, 1));
        b.addWatchNext(item("unknown", "wu", 0, 0, 1));
        b.addPreview(item("a", "pa", 1, 0, 0));
        b.addPreview(item("b", "pb", 1, 0, 0));
        assertTrue(b.watchNext().isEmpty());
        assertEquals(pkgs("a", "b"), b.watchNextPublishers());
        assertEquals(Arrays.asList("pb"), titles(b.mixed()));
        assertEquals(pkgs("a", "b"), b.mixedPublishers());
        assertTrue(new FeedBuilder(pkgs("a"), Collections.emptySet(), Collections.emptySet(), Collections.emptyMap())
                .watchNextPublishers().isEmpty());
    }

    private static List<String> sorted(List<String> l) {
        List<String> out = new ArrayList<>(l);
        Collections.sort(out);
        return out;
    }
}
