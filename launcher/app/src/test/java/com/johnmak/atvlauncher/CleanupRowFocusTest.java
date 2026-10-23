package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

public class CleanupRowFocusTest {
    private static CleanupList.Row row(String pkg, CleanupList.Col... cols) {
        java.util.Set<CleanupList.Col> set = EnumSet.noneOf(CleanupList.Col.class);
        set.addAll(Arrays.asList(cols));
        return new CleanupList.Row(pkg, pkg, null, true, true, cols.length == 0, set, CleanupList.NO_STATS, null);
    }

    private static final CleanupList.Col T = CleanupList.Col.TOGGLE, X = CleanupList.Col.TRASH, R = CleanupList.Col.ROLLBACK;

    private static List<CleanupList.Row> rows() {
        return Arrays.asList(row("full", T, X, R), row("locked"), row("toggleOnly", T), row("trashRb", X, R));
    }

    @Test public void leftRightClampWithinRowActions() {
        CleanupRowFocus f = new CleanupRowFocus(rows());
        assertEquals(T, f.col());
        assertFalse(f.move(CleanupRowFocus.Dir.LEFT));
        assertTrue(f.move(CleanupRowFocus.Dir.RIGHT));
        assertEquals(X, f.col());
        assertTrue(f.move(CleanupRowFocus.Dir.RIGHT));
        assertEquals(R, f.col());
        assertFalse(f.move(CleanupRowFocus.Dir.RIGHT));
    }

    @Test public void upDownKeepsColumnOrNearest() {
        CleanupRowFocus f = new CleanupRowFocus(rows());
        f.move(CleanupRowFocus.Dir.RIGHT);
        f.move(CleanupRowFocus.Dir.RIGHT); // ROLLBACK
        f.move(CleanupRowFocus.Dir.DOWN);  // locked row
        assertEquals(1, f.rowIndex());
        assertNull(f.col());
        assertFalse(f.move(CleanupRowFocus.Dir.RIGHT));
        f.move(CleanupRowFocus.Dir.DOWN);  // toggleOnly: no memory of old column after the locked row -> TOGGLE
        assertEquals(T, f.col());
        f.move(CleanupRowFocus.Dir.DOWN);  // trashRb: nearest to TOGGLE is TRASH
        assertEquals(X, f.col());
        assertFalse(f.move(CleanupRowFocus.Dir.DOWN));
        f.move(CleanupRowFocus.Dir.RIGHT); // ROLLBACK
        f.move(CleanupRowFocus.Dir.UP);    // toggleOnly: nearest to ROLLBACK is TOGGLE
        assertEquals(T, f.col());
        f.move(CleanupRowFocus.Dir.UP);
        f.move(CleanupRowFocus.Dir.UP);
        assertEquals(0, f.rowIndex());
        assertFalse(f.move(CleanupRowFocus.Dir.UP));
    }

    @Test public void confirmKeyAndTwoPress() {
        CleanupRowFocus f = new CleanupRowFocus(rows());
        CleanupConfirm c = new CleanupConfirm();
        assertEquals("TOGGLE:full", f.confirmKey());
        assertFalse(f.press(c));
        assertTrue(c.isArmed("TOGGLE:full"));
        assertTrue(f.press(c));
        f.move(CleanupRowFocus.Dir.RIGHT);
        assertFalse(f.press(c));
        f.move(CleanupRowFocus.Dir.LEFT);
        assertFalse(f.press(c)); // different key re-arms
        f.move(CleanupRowFocus.Dir.DOWN);
        assertNull(f.confirmKey());
        assertFalse(f.press(c));
    }

    @Test public void rebindKeepsFocusedPackageAndHandlesEmpty() {
        CleanupRowFocus f = new CleanupRowFocus(rows());
        f.move(CleanupRowFocus.Dir.DOWN);
        f.move(CleanupRowFocus.Dir.DOWN); // toggleOnly
        f.rebind(Arrays.asList(row("x", T), row("toggleOnly", T, X)));
        assertEquals(1, f.rowIndex());
        f.rebind(Arrays.asList(row("x", T)));
        assertEquals(0, f.rowIndex());
        f.rebind(Arrays.<CleanupList.Row>asList());
        assertEquals(-1, f.rowIndex());
        assertNull(f.row());
        assertFalse(f.move(CleanupRowFocus.Dir.DOWN));
        assertNull(f.confirmKey());
    }
}
