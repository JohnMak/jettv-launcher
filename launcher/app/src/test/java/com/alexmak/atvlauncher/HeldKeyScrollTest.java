package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * androidtv-yxp (hold LEFT/RIGHT: the row first keeps up, then lags far behind the focus).
 * Drives the real {@link RowScrollModel} the way MainActivity does per key repeat: the focused tile's scroll target is
 * {@code scrollFor(index, target)} and only the target moves, the motion is never restarted. The old per-press
 * smoothScrollBy(250 ms) restart lagged 800 px behind at 16 ms frames.
 */
public class HeldKeyScrollTest {
    private static final int REPEAT_MS = 50; // dumpsys input: KeyRepeatDelay 50ms
    private static final int TILE = 200;
    private static final int GAP = 24;
    private static final int TILES = 40;

    /**
     * Largest distance (px) the row still has to scroll when the next repeat arrives, i.e. the backlog that would
     * pile up if the row were slower than the key repeat.
     */
    private static double worstBacklog(int frameMs) {
        FavoritesRowLayout row = FavoritesRowLayout.compute(1700, 12, TILES, GAP, TILE, TILE);
        RowScrollModel model = new RowScrollModel();
        int index = 0;
        double worst = 0;
        for (long t = 0; t < (TILES - 1) * REPEAT_MS; t++) {
            if (t % REPEAT_MS == 0) {
                worst = Math.max(worst, Math.abs(model.remaining()));
                index++;
                int target = (int) Math.round(model.target());
                model.retargetBy(row.scrollFor(index, target) - target);
            }
            if (t % frameMs == 0) model.step(frameMs);
        }
        return worst;
    }

    @Test
    public void rowStaysWithinOneTileOfTheFocusWhileKeyIsHeld() {
        for (int frameMs : new int[]{16, 45}) {
            double lag = worstBacklog(frameMs);
            assertTrue("frame " + frameMs + " ms: row lags " + Math.round(lag) + " px behind the focus (tile " + (TILE + GAP) + " px)",
                    lag <= TILE + GAP);
        }
    }

    @Test
    public void rowReachesTheFinalTargetSoonAfterRelease() {
        FavoritesRowLayout row = FavoritesRowLayout.compute(1700, 12, TILES, GAP, TILE, TILE);
        RowScrollModel model = new RowScrollModel();
        model.retargetBy(row.scrollFor(20, 0));
        int ms = 0;
        while (!model.settled() && ms < 2000) {
            model.step(16);
            ms += 16;
        }
        assertTrue("settled after " + ms + " ms", ms <= 400);
        assertEquals(row.scrollFor(20, 0), model.pos(), 0.0);
    }
}
