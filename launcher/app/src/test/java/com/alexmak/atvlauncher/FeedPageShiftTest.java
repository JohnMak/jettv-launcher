package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedPageShiftTest {

    @Test
    public void firstRowKeepsItsHeadingAtTheTop() {
        assertEquals(430f, FeedPageShift.shiftDp(FocusModel.Zone.WATCH_NEXT, true), 0.01f);
        // the second row is visible under it
        float mixedRowBottom = FeedPageShift.FEED_TOP_DP + FeedPageShift.HEADING_DP + FeedPageShift.ROW_DP + FeedPageShift.INFO_DP
                + FeedPageShift.ROW_GAP_DP + FeedPageShift.HEADING_DP + FeedPageShift.ROW_DP
                - FeedPageShift.shiftDp(FocusModel.Zone.WATCH_NEXT, true);
        assertTrue(mixedRowBottom <= FeedPageShift.SCREEN_DP);
    }

    @Test
    public void secondRowAndItsInfoFitOnScreen() {
        float shift = FeedPageShift.shiftDp(FocusModel.Zone.MIXED, true);
        float infoBottom = FeedPageShift.FEED_TOP_DP + FeedPageShift.HEADING_DP + FeedPageShift.ROW_DP + FeedPageShift.INFO_DP
                + FeedPageShift.ROW_GAP_DP + FeedPageShift.HEADING_DP + FeedPageShift.ROW_DP + FeedPageShift.INFO_DP - shift;
        assertTrue(infoBottom <= FeedPageShift.SCREEN_DP - FeedPageShift.BOTTOM_MARGIN_DP + 0.01f);
        // first row's cards are still on screen (its top poster edge is below the screen top)
        float firstRowTop = FeedPageShift.FEED_TOP_DP + FeedPageShift.HEADING_DP - shift;
        assertTrue(firstRowTop >= 0);
    }

    @Test
    public void aloneSecondRowShiftsLikeTheFirst() {
        assertEquals(430f, FeedPageShift.shiftDp(FocusModel.Zone.MIXED, false), 0.01f);
    }
}
