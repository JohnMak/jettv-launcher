package com.johnmak.atvlauncher;

/**
 * Pure D-pad navigation over the home page.
 * <p>
 * Vertical order: top bar, favorites, watch-next row, mixed row. Every row remembers its own
 * column; empty rows are skipped; row ends clamp. Extra path: the weather item sits left of the top bar: LEFT
 * from the first top-bar item enters it, RIGHT leaves it to the first top-bar item, DOWN goes to favorites, UP
 * does nothing.
 * <p>
 * Feed rows have a hidden configure tile left of the first card: LEFT from the first card enters it, RIGHT returns,
 * UP/DOWN leave. A row whose cards are all hidden but that is configurable stays navigable and is entered on its tile.
 */
final class FocusModel {
    enum Zone { WEATHER, TOP, FAVORITES, WATCH_NEXT, MIXED }

    enum Dir { UP, DOWN, LEFT, RIGHT }

    private static final Zone[] ZONES = Zone.values();
    /** Vertical order of the zones; WEATHER sits beside it and is not part of it. */
    private static final Zone[] COLUMN = {Zone.TOP, Zone.FAVORITES, Zone.WATCH_NEXT, Zone.MIXED};

    private final int[] counts = new int[ZONES.length];
    private final int[] indices = new int[ZONES.length];
    private final boolean[] configurable = new boolean[ZONES.length];
    private Zone zone = Zone.FAVORITES;
    private boolean config;

    Zone zone() {
        return zone;
    }

    /** Whether focus is on the configure tile of the current feed row. */
    boolean config() {
        return config;
    }

    /** Which feed rows have a configure tile (they have publishing apps), whatever their card count. */
    void setConfigurable(boolean watchNext, boolean mixed) {
        configurable[Zone.WATCH_NEXT.ordinal()] = watchNext;
        configurable[Zone.MIXED.ordinal()] = mixed;
        normalize();
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
        normalize();
    }

    private void normalize() {
        for (int i = 0; i < ZONES.length; i++) indices[i] = clamp(indices[i], counts[i]);
        if (!navigable(zone)) {
            Zone up = above(zone);
            Zone down = below(zone);
            if (up != null) zone = up;
            else if (down != null) zone = down;
        }
        config = settleConfig();
    }

    private boolean settleConfig() {
        if (!isFeed(zone)) return false;
        if (counts[zone.ordinal()] == 0) return configurable[zone.ordinal()];
        return config && configurable[zone.ordinal()];
    }

    private boolean navigable(Zone z) {
        return counts[z.ordinal()] > 0 || (isFeed(z) && configurable[z.ordinal()]);
    }

    private static boolean isFeed(Zone z) {
        return z == Zone.WATCH_NEXT || z == Zone.MIXED;
    }

    /** Applies one D-pad press; returns whether the focus changed. */
    boolean move(Dir dir) {
        Zone beforeZone = zone;
        int beforeIndex = index();
        boolean beforeConfig = config;
        switch (dir) {
            case LEFT:
                if (config) break;
                if (isFeed(zone) && configurable[zone.ordinal()] && index() == 0) config = true;
                else if (zone == Zone.TOP && index() == 0 && counts[Zone.WEATHER.ordinal()] > 0) zone = Zone.WEATHER;
                else setIndex(index() - 1);
                break;
            case RIGHT:
                if (config) {
                    if (counts[zone.ordinal()] > 0) config = false;
                } else if (zone == Zone.WEATHER && counts[Zone.TOP.ordinal()] > 0) {
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
                    config = settleEntered();
                }
                break;
            }
            default: {
                Zone target = below(zone);
                if (target != null) {
                    zone = target;
                    config = settleEntered();
                }
            }
        }
        return zone != beforeZone || index() != beforeIndex || config != beforeConfig;
    }

    /** Entering a row: on the tile only when the row has no cards. */
    private boolean settleEntered() {
        return isFeed(zone) && counts[zone.ordinal()] == 0;
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
            if (navigable(COLUMN[i])) return COLUMN[i];
        }
        return null;
    }

    /** Nearest non-empty zone below. */
    private Zone below(Zone from) {
        for (int i = position(from) + 1; i < COLUMN.length; i++) {
            if (navigable(COLUMN[i])) return COLUMN[i];
        }
        return null;
    }

    private static int clamp(int v, int count) {
        return Math.max(0, Math.min(count - 1, v));
    }
}
