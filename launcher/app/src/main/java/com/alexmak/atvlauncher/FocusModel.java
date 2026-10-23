package com.alexmak.atvlauncher;

/**
 * Pure D-pad navigation over the home page.
 * <p>
 * Vertical order: top bar, favorites, watch-next row, mixed row. Every row remembers its own
 * column; empty rows are skipped; row ends clamp. Extra path: the weather item sits left of the top bar: LEFT
 * from the first top-bar item enters it, RIGHT leaves it to the first top-bar item, DOWN goes to favorites, UP
 * does nothing.
 */
final class FocusModel {
    enum Zone { WEATHER, TOP, FAVORITES, WATCH_NEXT, MIXED }

    enum Dir { UP, DOWN, LEFT, RIGHT }

    private static final Zone[] ZONES = Zone.values();
    /** Vertical order of the zones; WEATHER sits beside it and is not part of it. */
    private static final Zone[] COLUMN = {Zone.TOP, Zone.FAVORITES, Zone.WATCH_NEXT, Zone.MIXED};

    private final int[] counts = new int[ZONES.length];
    private final int[] indices = new int[ZONES.length];
    private Zone zone = Zone.FAVORITES;

    Zone zone() {
        return zone;
    }

    /** Index of the focused item inside the current zone. */
    int index() {
        return indices[zone.ordinal()];
    }

    int index(Zone z) {
        return indices[z.ordinal()];
    }

    void setIndex(int index) {
        indices[zone.ordinal()] = clamp(index, counts[zone.ordinal()]);
    }

    /** Caps the remembered column of a zone (e.g. back to the apps button when the home screen restarts). */
    void limitColumn(Zone z, int max) {
        indices[z.ordinal()] = Math.min(indices[z.ordinal()], max);
    }

    /** Updates zone sizes: clamps remembered columns and relocates focus if its zone became empty. */
    void setCounts(int weather, int top, int favorites, int watchNext, int mixed) {
        counts[Zone.WEATHER.ordinal()] = weather;
        counts[Zone.TOP.ordinal()] = top;
        counts[Zone.FAVORITES.ordinal()] = favorites;
        counts[Zone.WATCH_NEXT.ordinal()] = watchNext;
        counts[Zone.MIXED.ordinal()] = mixed;
        for (int i = 0; i < ZONES.length; i++) indices[i] = clamp(indices[i], counts[i]);
        if (counts[zone.ordinal()] == 0) {
            Zone up = above(zone);
            Zone down = below(zone);
            if (up != null) zone = up;
            else if (down != null) zone = down;
        }
    }

    /** Applies one D-pad press; returns whether the focus changed. */
    boolean move(Dir dir) {
        Zone beforeZone = zone;
        int beforeIndex = index();
        switch (dir) {
            case LEFT:
                if (zone == Zone.TOP && index() == 0 && counts[Zone.WEATHER.ordinal()] > 0) zone = Zone.WEATHER;
                else setIndex(index() - 1);
                break;
            case RIGHT:
                if (zone == Zone.WEATHER && counts[Zone.TOP.ordinal()] > 0) {
                    zone = Zone.TOP;
                    indices[Zone.TOP.ordinal()] = 0;
                } else {
                    setIndex(index() + 1);
                }
                break;
            case UP: {
                Zone target = above(zone);
                if (target != null) {
                    zone = target;
                    if (target == Zone.TOP) indices[target.ordinal()] = counts[target.ordinal()] - 1;
                }
                break;
            }
            default: {
                Zone target = below(zone);
                if (target != null) zone = target;
            }
        }
        return zone != beforeZone || index() != beforeIndex;
    }

    private static int position(Zone z) {
        if (z == Zone.WEATHER) return 0;
        for (int i = 0; i < COLUMN.length; i++) if (COLUMN[i] == z) return i;
        throw new IllegalStateException("zone " + z);
    }

    /** Nearest non-empty zone above. */
    private Zone above(Zone from) {
        if (from == Zone.WEATHER) return null;
        for (int i = position(from) - 1; i >= 0; i--) {
            if (counts[COLUMN[i].ordinal()] > 0) return COLUMN[i];
        }
        return null;
    }

    /** Nearest non-empty zone below. */
    private Zone below(Zone from) {
        for (int i = position(from) + 1; i < COLUMN.length; i++) {
            if (counts[COLUMN[i].ordinal()] > 0) return COLUMN[i];
        }
        return null;
    }

    private static int clamp(int v, int count) {
        return Math.max(0, Math.min(count - 1, v));
    }
}
