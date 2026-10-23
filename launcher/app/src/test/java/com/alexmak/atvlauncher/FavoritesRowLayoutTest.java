package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FavoritesRowLayoutTest {
    private static final int CONTENT = 1808;
    private static final int GAP = 16;
    private static final int MIN = 96;
    private static final int MAX = 220;

    private static FavoritesRowLayout of(int n) {
        return FavoritesRowLayout.compute(CONTENT, n, n, GAP, MIN, MAX);
    }

    private static void assertFlush(FavoritesRowLayout l, int n) {
        assertEquals(0, l.leftOf(0));
        assertEquals(CONTENT, l.leftOf(n - 1) + l.tileWidth);
        assertEquals(0, l.gapAfter(n - 1));
        for (int i = 0; i + 1 < n; i++) {
            assertEquals(l.leftOf(i + 1), l.leftOf(i) + l.tileWidth + l.gapAfter(i));
        }
    }

    @Test
    public void singleItemIsFlushLeftAtMaxSize() {
        FavoritesRowLayout l = of(1);
        assertEquals(MAX, l.tileWidth);
        assertEquals(124, l.tileHeight);
        assertEquals(0, l.leftOf(0));
        assertFalse(l.scrolls);
    }

    @Test
    public void fewItemsAreCappedAndSpreadEdgeToEdge() {
        FavoritesRowLayout l = of(2);
        assertEquals(MAX, l.tileWidth);
        assertEquals(CONTENT - 2 * MAX, l.gapAfter(0));
        assertFlush(l, 2);
    }

    @Test
    public void mediumRowsShrinkAndKeepEqualGaps() {
        FavoritesRowLayout l = of(5);
        assertEquals(MAX, l.tileWidth);
        assertFlush(l, 5);
        FavoritesRowLayout eight = of(8);
        assertEquals((CONTENT - 7 * GAP) / 8, eight.tileWidth);
        assertTrue(eight.tileWidth < MAX);
        assertFlush(eight, 8);
        assertFalse(eight.scrolls);
    }

    @Test
    public void tooManyItemsScrollAtMinSizeWithMinGap() {
        FavoritesRowLayout l = of(20);
        assertTrue(l.scrolls);
        assertEquals(MIN, l.tileWidth);
        assertEquals(54, l.tileHeight);
        assertEquals(GAP, l.gapAfter(0));
        assertEquals(0, l.gapAfter(19));
    }

    @Test
    public void continuationKeepsSizeAndGapAndFitsOnlyFavoritesAndButton() {
        // 8 fitting tiles (7 favorites + apps button), 5 more frequent apps after the button
        FavoritesRowLayout fit = of(8);
        FavoritesRowLayout l = FavoritesRowLayout.compute(CONTENT, 8, 13, GAP, MIN, MAX);
        assertEquals(fit.tileWidth, l.tileWidth);
        assertEquals(CONTENT, l.leftOf(7) + l.tileWidth); // the apps button is still flush right
        assertEquals(fit.gapAfter(0), l.gapAfter(7));     // the continuation uses the same gap
        assertEquals(l.leftOf(8), l.leftOf(7) + l.tileWidth + l.gapAfter(7));
        assertEquals(0, l.gapAfter(12));
        assertFalse(l.scrolls);
    }

    @Test
    public void scrollTargetKeepsFocusedTileVisibleAndResetsAtTheButton() {
        FavoritesRowLayout l = FavoritesRowLayout.compute(CONTENT, 8, 13, GAP, MIN, MAX);
        int w = l.tileWidth;
        for (int i = 0; i <= 7; i++) assertEquals(0, l.scrollFor(i, 0)); // favorites and button are aligned
        assertEquals(0, l.scrollFor(7, 500));                              // coming back to the button resets
        int first = l.scrollFor(8, 0);                                      // first continuation tile: right edge on the margin
        assertEquals(l.leftOf(8) + w - CONTENT, first);
        assertEquals(first, l.scrollFor(8, first));                         // already visible: no change
        int second = l.scrollFor(9, first);
        assertEquals(l.leftOf(9) + w - CONTENT, second);
        assertEquals(second, l.scrollFor(8, second));                       // still visible while moving left
        assertEquals(l.leftOf(12) + w - CONTENT, l.scrollFor(12, 0));       // last tile: exactly the end of the row
        assertEquals(l.leftOf(12) + w - CONTENT, l.scrollFor(12, 10_000));  // never scrolls past the end
    }

    @Test
    public void scrollTargetWhenFavoritesThemselvesScrollIsMinimalMove() {
        FavoritesRowLayout l = FavoritesRowLayout.compute(CONTENT, 20, 25, GAP, MIN, MAX);
        assertTrue(l.scrolls);
        assertEquals(0, l.scrollFor(3, 0));
        int target = l.scrollFor(19, 0);
        assertEquals(l.leftOf(19) + MIN - CONTENT, target);
        assertEquals(l.leftOf(2), l.scrollFor(2, target)); // left of the window: tile flush left
        assertEquals(0, l.scrollFor(0, target));
    }
}
