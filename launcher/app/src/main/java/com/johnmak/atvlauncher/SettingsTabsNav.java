package com.johnmak.atvlauncher;

/**
 * Pure D-pad navigation of the settings screen: warning rows above, the tab strip, and the selected tab's list.
 * Keys map a state to a new state; the activity renders it and runs row actions (OK, LEFT/RIGHT in lists).
 */
final class SettingsTabsNav {
    static final int TAB_COUNT = 4;

    enum Zone { TOP_ROWS, TABS, LIST }

    enum Key { UP, DOWN, LEFT, RIGHT }

    /** Immutable focus state. {@code row} indexes the top rows in TOP_ROWS and the tab's list in LIST; 0 in TABS. */
    static final class State {
        final Zone zone;
        final int tab;
        final int row;

        State(Zone zone, int tab, int row) {
            this.zone = zone;
            this.tab = tab;
            this.row = row;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof State)) return false;
            State s = (State) o;
            return zone == s.zone && tab == s.tab && row == s.row;
        }

        @Override
        public int hashCode() {
            return (zone.ordinal() * 31 + tab) * 31 + row;
        }

        @Override
        public String toString() {
            return zone + "/" + tab + "/" + row;
        }
    }

    private SettingsTabsNav() {}

    static State tabs(int tab) {
        return new State(Zone.TABS, tab, 0);
    }

    static State next(State s, Key key, int topRows, int listRows) {
        switch (s.zone) {
            case TOP_ROWS:
                if (key == Key.UP) return new State(Zone.TOP_ROWS, s.tab, Math.max(0, s.row - 1));
                if (key == Key.DOWN) {
                    return s.row + 1 < topRows ? new State(Zone.TOP_ROWS, s.tab, s.row + 1) : tabs(s.tab);
                }
                return s;
            case TABS:
                if (key == Key.LEFT) return tabs(Math.max(0, s.tab - 1));
                if (key == Key.RIGHT) return tabs(Math.min(TAB_COUNT - 1, s.tab + 1));
                if (key == Key.UP) return topRows > 0 ? new State(Zone.TOP_ROWS, s.tab, topRows - 1) : s;
                if (key == Key.DOWN) return listRows > 0 ? new State(Zone.LIST, s.tab, 0) : s;
                return s;
            default:
                if (key == Key.UP) return s.row > 0 ? new State(Zone.LIST, s.tab, s.row - 1) : tabs(s.tab);
                if (key == Key.DOWN) return new State(Zone.LIST, s.tab, Math.min(listRows - 1, s.row + 1));
                return s;
        }
    }

    /** BACK in a list returns to the tab strip; elsewhere the screen closes (see {@link #backCloses}). */
    static State back(State s) {
        return s.zone == Zone.LIST ? tabs(s.tab) : s;
    }

    static boolean backCloses(State s) {
        return s.zone != Zone.LIST;
    }

    /** Keeps a state valid after the number of rows changed (a warning row disappeared, a list was rebuilt). */
    static State clamp(State s, int topRows, int listRows) {
        int tab = Math.max(0, Math.min(TAB_COUNT - 1, s.tab));
        if (s.zone == Zone.TOP_ROWS) {
            return topRows > 0 ? new State(Zone.TOP_ROWS, tab, Math.min(s.row, topRows - 1)) : tabs(tab);
        }
        if (s.zone == Zone.LIST) {
            return listRows > 0 ? new State(Zone.LIST, tab, Math.min(s.row, listRows - 1)) : tabs(tab);
        }
        return tabs(tab);
    }
}
