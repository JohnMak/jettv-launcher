package com.alexmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class TimezoneLabelTest {
    private static final long JAN = 1_704_067_200_000L; // 2024-01-01T00:00Z
    private static final long JUL = 1_719_792_000_000L; // 2024-07-01T00:00Z

    @Test public void formatsOffsets() {
        assertEquals("UTC+7", TimezoneLabel.of("Asia/Novosibirsk", JAN));
        assertEquals("UTC+3", TimezoneLabel.of("Europe/Moscow", JAN));
        assertEquals("UTC+5:30", TimezoneLabel.of("Asia/Kolkata", JAN));
        assertEquals("UTC", TimezoneLabel.of("UTC", JAN));
        assertEquals("UTC−5", TimezoneLabel.of("America/New_York", JAN));
    }

    @Test public void followsDaylightSaving() {
        assertEquals("UTC+1", TimezoneLabel.of("Europe/Berlin", JAN));
        assertEquals("UTC+2", TimezoneLabel.of("Europe/Berlin", JUL));
    }

    @Test public void knowsOnlyRealZones() {
        assertTrue(TimezoneLabel.isKnown("Europe/Berlin"));
        assertFalse(TimezoneLabel.isKnown("Mars/Olympus"));
        assertFalse(TimezoneLabel.isKnown(""));
    }
}
