package com.johnmak.atvlauncher;

/**
 * What the favorites row shows: {@code favorites} app tiles, the "all apps" button, then {@code frequent} more app tiles
 * (the continuation). Nothing is shown (zero bindable items) until the row geometry is known, so a tile is never bound
 * without its size. App entries are the favorites followed by the frequent apps; the button has none.
 */
final class FavoritesRowState {
    enum Kind { FAVORITE, APPS_BUTTON, FREQUENT }

    private int favorites;
    private int frequent;
    private FavoritesRowLayout geometry;

    void setApps(int favorites, int frequent) {
        this.favorites = favorites;
        this.frequent = frequent;
    }

    void setGeometry(FavoritesRowLayout geometry) {
        this.geometry = geometry;
    }

    /** Tiles that exactly fill the content width: favorites plus the apps button. */
    int fitCount() {
        return favorites + 1;
    }

    /** Position of the apps button. */
    int buttonIndex() {
        return favorites;
    }

    /** Tiles the row will hold once laid out, continuation included. Used for focus and geometry. */
    int tileCount() {
        return favorites + 1 + frequent;
    }

    Kind kind(int position) {
        if (position < favorites) return Kind.FAVORITE;
        return position == favorites ? Kind.APPS_BUTTON : Kind.FREQUENT;
    }

    /** Index into the entries (favorites, then frequent apps) of an app tile; the apps button has none. */
    int entryIndex(int position) {
        if (kind(position) == Kind.APPS_BUTTON) throw new IllegalArgumentException("the apps button has no entry");
        return position < favorites ? position : position - 1;
    }

    /** Items the adapter exposes right now: none until geometry exists. */
    int visibleCount() {
        return geometry == null ? 0 : tileCount();
    }

    /** Geometry for binding; only valid while {@link #visibleCount()} is positive. */
    FavoritesRowLayout geometry() {
        if (geometry == null) throw new IllegalStateException("geometry not set");
        return geometry;
    }
}
