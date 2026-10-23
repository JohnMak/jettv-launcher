package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChecklistWindowTest {
    @Test
    public void windowScrollsOnlyWhenSelectionLeavesIt() {
        ChecklistWindow w = new ChecklistWindow(20);
        assertEquals(0, w.first());
        assertEquals(8, w.end());
        for (int i = 0; i < 7; i++) assertTrue(w.move(1));
        assertEquals(0, w.first());
        assertTrue(w.move(1)); // 8th index leaves the window
        assertEquals(8, w.selected());
        assertEquals(1, w.first());
        assertEquals(9, w.end());
        assertTrue(w.isVisible(8));
        assertFalse(w.isVisible(0));
        for (int i = 0; i < 30; i++) w.move(1);
        assertEquals(19, w.selected());
        assertEquals(12, w.first());
        assertFalse(w.move(1));
        for (int i = 0; i < 30; i++) w.move(-1);
        assertEquals(0, w.selected());
        assertEquals(0, w.first());
        assertFalse(w.move(-1));
    }

    @Test
    public void shortListShowsEverythingAndEmptyListIsSafe() {
        ChecklistWindow w = new ChecklistWindow(3);
        assertEquals(3, w.end());
        w.move(5);
        assertEquals(2, w.selected());
        assertEquals(0, w.first());
        ChecklistWindow empty = new ChecklistWindow(0);
        assertFalse(empty.move(1));
        assertEquals(0, empty.end());
    }
}
