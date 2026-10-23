package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SectionedGridFocusTest {
    private static SectionedGridFocus grid(int... sizes) {
        SectionedGridFocus g = new SectionedGridFocus(5);
        g.setSections(sizes);
        return g;
    }

    @Test
    public void flatPositionCountsHeaders() {
        SectionedGridFocus g = grid(7, 3);
        assertEquals(1, g.flatPosition());
        g.select(1, 2);
        assertEquals(11, g.flatPosition());
    }

    @Test
    public void downAndUpMoveOneRowAcrossSectionsKeepingColumn() {
        SectionedGridFocus g = grid(7, 3);
        g.move(FocusModel.Dir.RIGHT);
        g.move(FocusModel.Dir.RIGHT);
        g.move(FocusModel.Dir.RIGHT); // column 3
        assertTrue(g.move(FocusModel.Dir.DOWN)); // second row of section 0 has 2 items -> last one
        assertEquals(6, g.index());
        assertTrue(g.move(FocusModel.Dir.DOWN)); // into section 1, column 3 clamped to its 3 items
        assertEquals(1, g.section());
        assertEquals(2, g.index());
        assertFalse(g.move(FocusModel.Dir.DOWN));
        assertTrue(g.move(FocusModel.Dir.UP)); // back to the last row of section 0, column 3 -> clamped
        assertEquals(0, g.section());
        assertEquals(6, g.index());
    }

    @Test
    public void leftRightStayInsideTheRowAndTopClamps() {
        SectionedGridFocus g = grid(7, 3);
        assertFalse(g.move(FocusModel.Dir.LEFT));
        assertFalse(g.move(FocusModel.Dir.UP));
        for (int i = 0; i < 9; i++) g.move(FocusModel.Dir.RIGHT);
        assertEquals(4, g.index());
    }

    @Test
    public void upFromFirstRowEntersHeaderNearestToColumnAndDownReturns() {
        SectionedGridFocus g = grid(7, 3);
        g.setHeaderCount(3); // header buttons sit over columns 2..4
        assertTrue(g.move(FocusModel.Dir.UP)); // column 0 -> leftmost button
        assertTrue(g.inHeader());
        assertEquals(0, g.headerIndex());
        assertFalse(g.move(FocusModel.Dir.UP));
        assertFalse(g.move(FocusModel.Dir.LEFT));
        assertTrue(g.move(FocusModel.Dir.RIGHT));
        assertTrue(g.move(FocusModel.Dir.RIGHT));
        assertFalse(g.move(FocusModel.Dir.RIGHT));
        assertEquals(2, g.headerIndex());
        assertTrue(g.move(FocusModel.Dir.DOWN)); // rightmost button is above column 4
        assertFalse(g.inHeader());
        assertEquals(0, g.section());
        assertEquals(4, g.index());
        assertTrue(g.move(FocusModel.Dir.UP));
        assertEquals(2, g.headerIndex()); // column 4 -> rightmost
    }

    @Test
    public void noHeaderButtonsMeansUpStaysInGridAndEmptyingLeavesHeader() {
        SectionedGridFocus g = grid(7);
        assertFalse(g.move(FocusModel.Dir.UP));
        g.setHeaderCount(2);
        g.move(FocusModel.Dir.UP);
        assertTrue(g.inHeader());
        g.setHeaderCount(0);
        assertFalse(g.inHeader());
    }

    @Test
    public void setSectionsClampsFocus() {
        SectionedGridFocus g = grid(7, 3);
        g.select(1, 2);
        g.setSections(new int[]{4});
        assertEquals(0, g.section());
        assertEquals(2, g.index());
        g.setSections(new int[]{});
        assertFalse(g.move(FocusModel.Dir.DOWN));
    }
}
