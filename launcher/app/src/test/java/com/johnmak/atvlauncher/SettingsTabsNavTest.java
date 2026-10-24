package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.johnmak.atvlauncher.SettingsTabsNav.Key;
import com.johnmak.atvlauncher.SettingsTabsNav.State;
import com.johnmak.atvlauncher.SettingsTabsNav.Zone;

import org.junit.Test;

public class SettingsTabsNavTest {
    private static State top(int tab, int row) {
        return new State(Zone.TOP_ROWS, tab, row);
    }

    private static State list(int tab, int row) {
        return new State(Zone.LIST, tab, row);
    }

    private static State go(State s, Key k, int topRows, int listRows) {
        return SettingsTabsNav.next(s, k, topRows, listRows);
    }

    @Test
    public void tabsSwitchWithLeftRightAndClamp() {
        assertEquals(SettingsTabsNav.tabs(1), go(SettingsTabsNav.tabs(0), Key.RIGHT, 0, 5));
        assertEquals(SettingsTabsNav.tabs(0), go(SettingsTabsNav.tabs(0), Key.LEFT, 0, 5));
        assertEquals(SettingsTabsNav.tabs(3), go(SettingsTabsNav.tabs(3), Key.RIGHT, 0, 5));
        assertEquals(SettingsTabsNav.tabs(1), go(SettingsTabsNav.tabs(2), Key.LEFT, 0, 5));
    }

    @Test
    public void downEntersListAndUpFromFirstRowReturnsToTabs() {
        assertEquals(list(2, 0), go(SettingsTabsNav.tabs(2), Key.DOWN, 0, 3));
        assertEquals(SettingsTabsNav.tabs(2), go(SettingsTabsNav.tabs(2), Key.DOWN, 0, 0));
        assertEquals(list(2, 1), go(list(2, 0), Key.DOWN, 0, 3));
        assertEquals(list(2, 2), go(list(2, 2), Key.DOWN, 0, 3));
        assertEquals(list(2, 0), go(list(2, 1), Key.UP, 0, 3));
        assertEquals(SettingsTabsNav.tabs(2), go(list(2, 0), Key.UP, 0, 3));
    }

    @Test
    public void listIgnoresLeftRightForTheRowHandlers() {
        assertEquals(list(1, 1), go(list(1, 1), Key.LEFT, 0, 3));
        assertEquals(list(1, 1), go(list(1, 1), Key.RIGHT, 0, 3));
    }

    @Test
    public void warningRowsSitAboveTheTabs() {
        assertEquals(top(0, 1), go(SettingsTabsNav.tabs(0), Key.UP, 2, 5));
        assertEquals(top(0, 0), go(top(0, 1), Key.UP, 2, 5));
        assertEquals(top(0, 0), go(top(0, 0), Key.UP, 2, 5));
        assertEquals(top(0, 1), go(top(0, 0), Key.DOWN, 2, 5));
        assertEquals(SettingsTabsNav.tabs(0), go(top(0, 1), Key.DOWN, 2, 5));
        assertEquals(top(0, 0), go(top(0, 0), Key.LEFT, 2, 5));
        assertEquals(SettingsTabsNav.tabs(0), go(SettingsTabsNav.tabs(0), Key.UP, 0, 5));
    }

    @Test
    public void backLeavesListThenCloses() {
        assertFalse(SettingsTabsNav.backCloses(list(1, 2)));
        assertEquals(SettingsTabsNav.tabs(1), SettingsTabsNav.back(list(1, 2)));
        assertTrue(SettingsTabsNav.backCloses(SettingsTabsNav.tabs(1)));
        assertTrue(SettingsTabsNav.backCloses(top(0, 0)));
    }

    @Test
    public void clampKeepsStateValidWhenRowsChange() {
        assertEquals(SettingsTabsNav.tabs(0), SettingsTabsNav.clamp(top(0, 1), 0, 5));
        assertEquals(top(0, 0), SettingsTabsNav.clamp(top(0, 1), 1, 5));
        assertEquals(list(1, 2), SettingsTabsNav.clamp(list(1, 7), 0, 3));
        assertEquals(SettingsTabsNav.tabs(1), SettingsTabsNav.clamp(list(1, 0), 0, 0));
    }
}
