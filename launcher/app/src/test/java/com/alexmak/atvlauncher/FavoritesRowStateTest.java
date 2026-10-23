package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

public class FavoritesRowStateTest {
    private static FavoritesRowLayout geo(int n) {
        return FavoritesRowLayout.compute(1808, n, n, 16, 96, 220);
    }

    @Test
    public void nothingIsBindableUntilGeometryIsKnown() {
        FavoritesRowState s = new FavoritesRowState();
        s.setApps(5, 0);
        assertEquals(0, s.visibleCount());
        assertEquals(6, s.tileCount());
        try {
            s.geometry();
            fail("geometry must not be readable before layout");
        } catch (IllegalStateException expected) {
            // binding without geometry is the cold-start crash this guards against
        }
        FavoritesRowLayout g = geo(6);
        s.setGeometry(g);
        assertEquals(6, s.visibleCount());
        assertSame(g, s.geometry());
        s.setApps(7, 0);
        assertEquals(8, s.visibleCount());
        assertEquals(8, s.tileCount());
    }

    @Test
    public void continuationFollowsTheAppsButton() {
        FavoritesRowState s = new FavoritesRowState();
        s.setApps(3, 4);
        assertEquals(8, s.tileCount());
        assertEquals(4, s.fitCount());
        assertEquals(3, s.buttonIndex());
        assertEquals(FavoritesRowState.Kind.FAVORITE, s.kind(0));
        assertEquals(FavoritesRowState.Kind.FAVORITE, s.kind(2));
        assertEquals(FavoritesRowState.Kind.APPS_BUTTON, s.kind(3));
        assertEquals(FavoritesRowState.Kind.FREQUENT, s.kind(4));
        assertEquals(FavoritesRowState.Kind.FREQUENT, s.kind(7));
        assertEquals(2, s.entryIndex(2));
        assertEquals(3, s.entryIndex(4)); // entries are favorites then frequent apps; the button has none
        assertEquals(6, s.entryIndex(7));
        s.setApps(0, 0);
        assertEquals(1, s.tileCount());
        assertEquals(FavoritesRowState.Kind.APPS_BUTTON, s.kind(0));
    }
}
