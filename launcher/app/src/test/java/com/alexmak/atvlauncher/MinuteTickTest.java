package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import java.util.SimpleTimeZone;
import java.util.TimeZone;
import org.junit.Test;

public class MinuteTickTest {
    @Test
    public void delayIsMeasuredOnTheWallClockOfTheZone() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        assertEquals(60_000, MinuteTick.delayMillis(0, utc));
        assertEquals(1, MinuteTick.delayMillis(59_999, utc));
        assertEquals(60_000 - 10_000, MinuteTick.delayMillis(10_000, TimeZone.getTimeZone("Asia/Kolkata")));
        // a zone whose offset is not a whole number of minutes: the wall-clock minute changes at another instant
        TimeZone odd = new SimpleTimeZone(20_000, "odd");
        assertEquals(40_000, MinuteTick.delayMillis(0, odd));
        assertEquals(60_000, MinuteTick.delayMillis(40_000, odd));
    }
}
