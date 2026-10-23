package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedPageShiftTest {

    @Test
    public void firstRowKeepsItsHeadingAtTheTop() {
        assertEquals(430f, FeedPageShift.shiftDp(FocusModel.Zone.WATCH_NEXT, true, 100), 0.01f);
        // the second row is visible under it
        int row = PreviewScale.rowHeightDp(100);
        float mixedRowBottom = FeedPageShift.FEED_TOP_DP + FeedPageShift.HEADING_DP + row + FeedPageShift.INFO_DP
                + FeedPageShift.ROW_GAP_DP + FeedPageShift.HEADING_DP + row
                - FeedPageShift.shiftDp(FocusModel.Zone.WATCH_NEXT, true, 100);
        assertTrue(mixedRowBottom <= FeedPageShift.SCREEN_DP);
    }

    @Test
    public void secondRowAndItsInfoFitOnScreenAtEveryPreviewScale() {
        for (int percent : PreviewScale.PERCENTS) {
            float shift = FeedPageShift.shiftDp(FocusModel.Zone.MIXED, true, percent);
            float infoBottom = FeedPageShift.mixedInfoBottomDp(percent) - shift;
            assertTrue("scale " + percent, infoBottom <= FeedPageShift.SCREEN_DP - FeedPageShift.BOTTOM_MARGIN_DP + 0.01f);
            assertTrue("scale " + percent, shift >= 430f - 0.01f);
        }
        // at 100 % the first row's cards are still on screen (its top poster edge is below the screen top)
        float shift = FeedPageShift.shiftDp(FocusModel.Zone.MIXED, true, 100);
        assertEquals(FeedPageShift.mixedInfoBottomDp(100) - 528f, shift, 0.01f);
        assertTrue(FeedPageShift.FEED_TOP_DP + FeedPageShift.HEADING_DP - shift >= 0);
    }

    @Test
    public void biggerCardsShiftTheSecondRowFurther() {
        assertTrue(FeedPageShift.shiftDp(FocusModel.Zone.MIXED, true, 150) > FeedPageShift.shiftDp(FocusModel.Zone.MIXED, true, 100));
        assertEquals(430f, FeedPageShift.shiftDp(FocusModel.Zone.MIXED, true, 70), 0.01f);
    }

    @Test
    public void configIconIsCenteredOnTheCardsAndShiftsRowByIconPlusGap() {
        assertEquals(46, FeedPageShift.configShiftDp());
        assertEquals(7f + (200f - 26f) / 2f, FeedPageShift.configIconTopDp(200f), 0.001f);
        // the glass around the icon (pad 12dp) must not touch the first card
        assertTrue(FeedPageShift.CONFIG_GAP_DP > Tiles.TOP_GLASS_PAD_X_DP);
    }

    @Test
    public void aloneSecondRowShiftsLikeTheFirst() {
        assertEquals(430f, FeedPageShift.shiftDp(FocusModel.Zone.MIXED, false, 100), 0.01f);
        assertEquals(430f, FeedPageShift.shiftDp(FocusModel.Zone.MIXED, false, 150), 0.01f);
    }
}
