package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FocusModelTest {
    private static FocusModel model(int top, int fav, int watch, int mixed) {
        FocusModel m = new FocusModel();
        m.setCounts(0, top, fav, watch, mixed);
        return m;
    }

    /** Full page: weather item present. */
    private static FocusModel full(int top, int fav, int watch, int mixed) {
        FocusModel m = new FocusModel();
        m.setCounts(1, top, fav, watch, mixed);
        return m;
    }

    @Test
    public void startsOnFavorites() {
        assertEquals(FocusModel.Zone.FAVORITES, model(3, 5, 4, 6).zone());
    }

    @Test
    public void topBarClampsAtBothEnds() {
        FocusModel m = model(5, 5, 4, 6); // VPN, Wi-Fi, sun, launcher settings, gear
        m.move(FocusModel.Dir.UP);
        assertEquals(FocusModel.Zone.TOP, m.zone());
        assertEquals(4, m.index()); // enters on the last top-bar item
        assertFalse(m.move(FocusModel.Dir.RIGHT));
        assertEquals(4, m.index());
        m.move(FocusModel.Dir.LEFT);
        m.move(FocusModel.Dir.LEFT);
        m.move(FocusModel.Dir.LEFT);
        m.move(FocusModel.Dir.LEFT);
        assertFalse(m.move(FocusModel.Dir.LEFT));
        assertEquals(0, m.index());
        assertFalse(m.move(FocusModel.Dir.UP));
    }

    @Test
    public void favoritesClampAndGoDownToWatchNextThenMixedAndBack() {
        FocusModel m = model(3, 5, 4, 6);
        assertFalse(m.move(FocusModel.Dir.LEFT));
        for (int i = 0; i < 10; i++) m.move(FocusModel.Dir.RIGHT);
        assertEquals(4, m.index());
        m.move(FocusModel.Dir.DOWN);
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());
        assertEquals(0, m.index());
        m.move(FocusModel.Dir.DOWN);
        assertEquals(FocusModel.Zone.MIXED, m.zone());
        assertFalse(m.move(FocusModel.Dir.DOWN));
        m.move(FocusModel.Dir.UP);
        m.move(FocusModel.Dir.UP);
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
        assertEquals(4, m.index()); // column memory per row
    }

    @Test
    public void everyRowRemembersItsOwnColumn() {
        FocusModel m = model(3, 5, 4, 6);
        m.move(FocusModel.Dir.RIGHT);
        m.move(FocusModel.Dir.RIGHT);
        m.move(FocusModel.Dir.DOWN);
        m.move(FocusModel.Dir.RIGHT);
        m.move(FocusModel.Dir.DOWN);
        for (int i = 0; i < 3; i++) m.move(FocusModel.Dir.RIGHT);
        assertEquals(3, m.index());
        m.move(FocusModel.Dir.UP);
        assertEquals(1, m.index());
        m.move(FocusModel.Dir.UP);
        assertEquals(2, m.index());
        m.move(FocusModel.Dir.DOWN);
        m.move(FocusModel.Dir.DOWN);
        assertEquals(3, m.index());
    }

    @Test
    public void emptyRowsAreSkipped() {
        FocusModel m = model(3, 5, 0, 6);
        m.move(FocusModel.Dir.DOWN);
        assertEquals(FocusModel.Zone.MIXED, m.zone());
        m.move(FocusModel.Dir.UP);
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());

        m = model(3, 5, 4, 0);
        m.move(FocusModel.Dir.DOWN);
        assertFalse(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());

        m = model(3, 5, 0, 0);
        assertFalse(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
    }

    @Test
    public void countChangeClampsIndexAndRelocatesFocusFromVanishedRow() {
        FocusModel m = model(3, 5, 4, 6);
        m.move(FocusModel.Dir.DOWN);
        m.move(FocusModel.Dir.DOWN);
        for (int i = 0; i < 5; i++) m.move(FocusModel.Dir.RIGHT);
        assertEquals(5, m.index());
        m.setCounts(0, 3, 5, 4, 3);
        assertEquals(2, m.index());
        m.setCounts(0, 3, 5, 4, 0);
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());
        m.setCounts(0, 3, 5, 0, 0);
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
    }

    @Test
    public void downFromTopLandsOnFavorites() {
        FocusModel m = model(3, 5, 4, 6);
        m.move(FocusModel.Dir.UP);
        assertEquals(FocusModel.Zone.TOP, m.zone());
        assertTrue(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
    }

    @Test
    public void weatherDownGoesToFavorites() {
        FocusModel m = full(3, 5, 4, 6);
        m.move(FocusModel.Dir.UP);
        m.move(FocusModel.Dir.LEFT);
        m.move(FocusModel.Dir.LEFT);
        m.move(FocusModel.Dir.LEFT);
        m.move(FocusModel.Dir.LEFT);
        assertEquals(FocusModel.Zone.WEATHER, m.zone());
        assertTrue(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
    }

    @Test
    public void setIndexFollowsExternalReorder() {
        FocusModel m = model(3, 5, 4, 6);
        m.setIndex(3);
        assertEquals(3, m.index());
        m.setIndex(99);
        assertEquals(4, m.index());
    }

    @Test
    public void coldStartIsFavoritesIndexZeroWhateverArrivesFirst() {
        FocusModel m = new FocusModel();
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
        assertEquals(0, m.index());
        m.setCounts(0, 3, 1, 0, 0);          // apps not loaded yet: only the apps button
        m.setCounts(0, 3, 1, 20, 30);        // feed arrives
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
        m.setCounts(0, 3, 6, 20, 30);        // favorites arrive
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
        assertEquals(0, m.index());
    }

    @Test
    public void weatherSitsLeftOfTopBarAndAboveFavorites() {
        FocusModel m = full(3, 5, 4, 6);
        m.move(FocusModel.Dir.UP);
        m.move(FocusModel.Dir.LEFT);
        m.move(FocusModel.Dir.LEFT);
        assertEquals(0, m.index());
        assertTrue(m.move(FocusModel.Dir.LEFT));
        assertEquals(FocusModel.Zone.WEATHER, m.zone());
        assertFalse(m.move(FocusModel.Dir.LEFT));
        assertFalse(m.move(FocusModel.Dir.UP));
        assertTrue(m.move(FocusModel.Dir.RIGHT));
        assertEquals(FocusModel.Zone.TOP, m.zone());
        assertEquals(0, m.index());
        m.move(FocusModel.Dir.LEFT);
        assertTrue(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.FAVORITES, m.zone());
        assertTrue(m.move(FocusModel.Dir.UP)); // up from favorites still goes to the top bar
        assertEquals(FocusModel.Zone.TOP, m.zone());
    }

    @Test
    public void favoritesColumnCoversContinuationAndCanBeResetToTheButton() {
        FocusModel m = model(3, 12, 4, 6); // 5 favorites + button + 6 frequent apps
        for (int i = 0; i < 20; i++) m.move(FocusModel.Dir.RIGHT);
        assertEquals(11, m.index());
        assertTrue(m.move(FocusModel.Dir.DOWN)); // DOWN from a continuation tile goes straight to the feed
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());
        m.move(FocusModel.Dir.UP);
        assertEquals(11, m.index());
        m.limitColumn(FocusModel.Zone.FAVORITES, 5);
        assertEquals(5, m.index());
        m.limitColumn(FocusModel.Zone.FAVORITES, 8);
        assertEquals(5, m.index());
    }

    private static FocusModel feedConfig(int watch, int mixed, boolean watchCfg, boolean mixedCfg) {
        FocusModel m = model(3, 5, watch, mixed);
        m.setConfigurable(watchCfg, mixedCfg);
        return m;
    }

    @Test
    public void leftFromFirstFeedCardEntersConfigTileAndRightReturns() {
        FocusModel m = feedConfig(4, 6, true, true);
        m.move(FocusModel.Dir.DOWN);
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());
        assertFalse(m.config());
        assertTrue(m.move(FocusModel.Dir.LEFT));
        assertTrue(m.config());
        assertFalse(m.move(FocusModel.Dir.LEFT)); // clamps at the tile
        assertTrue(m.move(FocusModel.Dir.RIGHT));
        assertFalse(m.config());
        assertEquals(0, m.index());
        m.move(FocusModel.Dir.RIGHT);
        m.move(FocusModel.Dir.LEFT);
        assertFalse(m.config()); // only the first card leads to the tile
        assertEquals(0, m.index());
    }

    @Test
    public void upAndDownLeaveTheConfigTile() {
        FocusModel m = feedConfig(4, 6, true, true);
        m.move(FocusModel.Dir.DOWN);
        m.move(FocusModel.Dir.LEFT);
        assertTrue(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.MIXED, m.zone());
        assertFalse(m.config());
        m.move(FocusModel.Dir.LEFT);
        assertTrue(m.config());
        assertTrue(m.move(FocusModel.Dir.UP));
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());
        assertFalse(m.config());
    }

    @Test
    public void rowWithoutConfigurablePublishersHasNoTile() {
        FocusModel m = feedConfig(4, 6, false, false);
        m.move(FocusModel.Dir.DOWN);
        assertFalse(m.move(FocusModel.Dir.LEFT));
        assertFalse(m.config());
    }

    @Test
    public void fullyExcludedRowIsNavigableAndLandsOnItsTile() {
        FocusModel m = feedConfig(0, 6, true, true);
        assertTrue(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());
        assertTrue(m.config());
        assertFalse(m.move(FocusModel.Dir.RIGHT)); // no cards to return to
        assertFalse(m.move(FocusModel.Dir.LEFT));
        assertTrue(m.move(FocusModel.Dir.DOWN));
        assertEquals(FocusModel.Zone.MIXED, m.zone());
        assertFalse(m.config());
        assertTrue(m.move(FocusModel.Dir.UP));
        assertTrue(m.config());
    }

    @Test
    public void emptyRowWithoutPublishersIsStillSkipped() {
        FocusModel m = feedConfig(0, 6, false, true);
        m.move(FocusModel.Dir.DOWN);
        assertEquals(FocusModel.Zone.MIXED, m.zone());
    }

    @Test
    public void countChangeEmptyingTheFocusedRowKeepsFocusOnItsTile() {
        FocusModel m = feedConfig(4, 6, true, true);
        m.move(FocusModel.Dir.DOWN);
        m.setCounts(0, 3, 5, 0, 6); // every card excluded
        assertEquals(FocusModel.Zone.WATCH_NEXT, m.zone());
        assertTrue(m.config());
        m.setCounts(0, 3, 5, 4, 6); // re-enabled: still on the tile
        assertTrue(m.config());
        m.setConfigurable(false, true);
        assertFalse(m.config());
    }
}
