package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeedInfoLineTest {
    @Test
    public void fieldIsVisibleOnlyForItsOwnRow() {
        for (FocusModel.Zone focused : FocusModel.Zone.values()) {
            assertEquals(focused == FocusModel.Zone.WATCH_NEXT,
                    FeedInfoLine.visible(FocusModel.Zone.WATCH_NEXT, focused));
            assertEquals(focused == FocusModel.Zone.MIXED,
                    FeedInfoLine.visible(FocusModel.Zone.MIXED, focused));
        }
        assertTrue(FeedInfoLine.visible(FocusModel.Zone.MIXED, FocusModel.Zone.MIXED));
        assertFalse(FeedInfoLine.visible(FocusModel.Zone.WATCH_NEXT, FocusModel.Zone.FAVORITES));
    }

    @Test
    public void seasonPartIsOmittedForMovies() {
        assertEquals("", FeedInfoLine.season("", ""));
        assertEquals("Сезон 2", FeedInfoLine.season("2", ""));
        assertEquals("Серия 5", FeedInfoLine.season("", "5"));
        assertEquals("Сезон 2, серия 5", FeedInfoLine.season("2", "5"));
    }

    @Test
    public void yearIsEmptyWhenUnknown() {
        assertEquals("", FeedInfoLine.year(0));
        assertEquals("2008", FeedInfoLine.year(2008));
    }
}
