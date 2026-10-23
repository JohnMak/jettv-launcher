package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.alexmak.atvlauncher.SelectionDelta.Row;
import com.alexmak.atvlauncher.SelectionDelta.Selection;
import com.alexmak.atvlauncher.SelectionDelta.ViewRef;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * RED for androidtv-yxp: every held-key repeat restarted animations on every card of every row.
 * Spec: only the previously focused and the newly focused view change visual state; the rest of the screen is untouched.
 */
public class SelectionDeltaTest {
    private static final int[] SIZES = {6, 12, 8, 8, 3}; // TOP, FAVORITES, WATCH_NEXT, MIXED, WEATHER

    private static Selection sel(Row zone, int top, int fav, int watch, int mixed, int weather) {
        return new Selection(zone, new int[]{top, fav, watch, mixed, weather}, SIZES);
    }

    private static Set<ViewRef> set(ViewRef... refs) {
        Set<ViewRef> s = new HashSet<>();
        for (ViewRef r : refs) s.add(r);
        return s;
    }

    private static ViewRef v(Row row, int i) {
        return new ViewRef(row, i);
    }

    @Test
    public void moveWithinRowChangesOnlyTheTwoViewsAndOnlyText() {
        SelectionDelta d = SelectionDelta.between(sel(Row.FAVORITES, 0, 3, 0, 0, 0), sel(Row.FAVORITES, 0, 4, 0, 0, 0));
        assertEquals("changed views", set(v(Row.FAVORITES, 3), v(Row.FAVORITES, 4)), d.changedViews);
        assertFalse("info field must not fade inside one row", d.rowChanged);
        assertTrue("info text must be replaced", d.textChanged);
    }

    @Test
    public void moveWithinFeedRowDoesNotTouchOtherRows() {
        SelectionDelta d = SelectionDelta.between(sel(Row.WATCH_NEXT, 0, 5, 2, 1, 0), sel(Row.WATCH_NEXT, 0, 5, 3, 1, 0));
        assertEquals("changed views", set(v(Row.WATCH_NEXT, 2), v(Row.WATCH_NEXT, 3)), d.changedViews);
        assertFalse(d.rowChanged);
    }

    @Test
    public void moveBetweenRowsChangesOldAndNewFocusedViewsAndFadesInfo() {
        SelectionDelta d = SelectionDelta.between(sel(Row.FAVORITES, 0, 4, 2, 0, 0), sel(Row.WATCH_NEXT, 0, 4, 2, 0, 0));
        assertEquals("changed views", set(v(Row.FAVORITES, 4), v(Row.WATCH_NEXT, 2)), d.changedViews);
        assertTrue("info field fades on row change", d.rowChanged);
        assertTrue(d.textChanged);
    }

    @Test
    public void unchangedSelectionChangesNothing() {
        Selection s = sel(Row.MIXED, 0, 1, 2, 3, 0);
        SelectionDelta d = SelectionDelta.between(s, sel(Row.MIXED, 0, 1, 2, 3, 0));
        assertTrue("changed views: " + d.changedViews, d.changedViews.isEmpty());
        assertFalse(d.rowChanged);
        assertFalse(d.textChanged);
    }

    @Test
    public void firstApplicationEstablishesTheStateOfAllViews() {
        SelectionDelta d = SelectionDelta.between(null, sel(Row.FAVORITES, 0, 0, 0, 0, 0));
        int total = 0;
        for (int n : SIZES) total += n;
        assertEquals("all views get their initial state", total, d.changedViews.size());
        assertTrue(d.rowChanged);
        assertTrue(d.textChanged);
    }
}
