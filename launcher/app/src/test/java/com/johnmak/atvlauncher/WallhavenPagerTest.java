package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WallhavenPagerTest {
    private static WallhavenImage img(String id) {
        return new WallhavenImage(id, "https://w.wallhaven.cc/full/x/" + id + ".jpg", "3840x2160");
    }

    private static WallhavenSearch.Page page(String seed, int page, int lastPage, String... ids) {
        List<WallhavenImage> l = new ArrayList<>();
        for (String id : ids) l.add(img(id));
        return new WallhavenSearch.Page(l, seed, page, lastPage);
    }

    private static String idOf(WallhavenImage i) {
        return i == null ? null : i.id;
    }

    @Test public void startYieldsFirstPageInOrderAndTracksSeedAndSeen() {
        WallhavenPager p = new WallhavenPager();
        p.start(page("s1", 1, 3, "a", "b"));
        assertEquals("s1", p.seed());
        assertEquals(1, p.curPage());
        assertEquals("a", idOf(p.current()));
        p.consume();
        assertEquals("b", idOf(p.current()));
        assertEquals("both first-page ids are remembered for no-repeat", 2, p.seenCount());
    }

    @Test public void prefetchesOneAheadThenPromotesAndEvictsTheConsumedPage() {
        WallhavenPager p = new WallhavenPager();
        p.start(page("s1", 1, 3, "a", "b"));
        assertTrue("a next page is reachable → prefetch it", p.needsFetch());
        WallhavenPager.Fetch f = p.plannedFetch();
        assertEquals("same seed carried", "s1", f.seed);
        assertEquals("the next page", 2, f.page);
        assertFalse(f.fresh);

        p.onPage(page("s1", 2, 3, "c", "d"), false);
        assertTrue(p.hasNext());
        assertFalse("one-ahead is satisfied — no further fetch while next is buffered", p.needsFetch());

        p.consume();                              // a
        p.consume();                              // b → page 1 exhausted → promote page 2, evict page 1
        assertEquals("promoted to page 2", 2, p.curPage());
        assertEquals("c", idOf(p.current()));
        assertFalse("page 1 is evicted, not buffered", p.hasNext());
        assertTrue("now the following page is prefetchable", p.needsFetch());
        assertEquals(3, p.plannedFetch().page);
    }

    @Test public void endOfResultsStartsAFreshSeedAndResetsSeen() {
        WallhavenPager p = new WallhavenPager();
        p.start(page("s1", 1, 1, "a", "b"));      // single-page seed (lastPage == 1)
        assertFalse("still showing the only page — no fetch while images remain", p.needsFetch());
        p.consume();                              // a
        p.consume();                              // b → seed fully shown
        assertTrue("seed exhausted → a fetch is needed", p.needsFetch());
        WallhavenPager.Fetch f = p.plannedFetch();
        assertTrue("it is a FRESH-seed request", f.fresh);
        assertEquals("", f.seed);
        assertEquals(1, f.page);

        p.onPage(page("s2", 1, 5, "a", "e"), true); // fresh seed; "a" repeats across the seed boundary (allowed)
        assertEquals("s2", p.seed());
        assertEquals("seen-id reset to just the new seed's ids", 2, p.seenCount());
        assertEquals("a", idOf(p.current()));     // the new seed's first image
        assertEquals(1, p.curPage());
    }

    @Test public void noRepeatWithinASeedDropsDuplicateIds() {
        WallhavenPager p = new WallhavenPager();
        p.start(page("s1", 1, 3, "a", "b"));
        p.onPage(page("s1", 2, 3, "b", "c"), false); // "b" already seen → dropped from the next page
        p.consume();                                 // a
        p.consume();                                 // b → promote page 2 (which is just [c], b was deduped out)
        assertEquals("the duplicate b was dropped; c shows next", "c", idOf(p.current()));
        assertEquals(3, p.seenCount());              // a, b, c
    }

    @Test public void validEmptyPageAdvancesToTheFollowingPage() {
        WallhavenPager p = new WallhavenPager();
        p.start(page("s1", 1, 3, "a"));
        p.consume();                                 // a → page 1 exhausted
        assertTrue(p.needsFetch());
        assertEquals(2, p.plannedFetch().page);
        p.onPage(page("s1", 2, 3, "a"), false);      // page 2 is all-duplicate → empty after dedup (valid-empty)
        assertNull("the empty page yields nothing to show", p.current());
        assertTrue("valid-empty-page → advance to the following page", p.needsFetch());
        assertEquals("the page AFTER the empty one", 3, p.plannedFetch().page);
        p.onPage(page("s1", 3, 3, "d"), false);
        assertEquals("d", idOf(p.current()));
    }

    @Test public void consumeOnAnEmptyWindowIsSafe() {
        WallhavenPager p = new WallhavenPager();
        p.start(page("s1", 1, 0));                   // valid-empty first page
        assertNull(p.current());
        p.consume();                                 // must not throw
        assertNull(p.current());
    }
}
