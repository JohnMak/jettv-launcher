package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatsFormatTest {
    @Test public void bytesPickTheUnit() {
        assertEquals("0 КБ", StatsFormat.bytes(0L));
        assertEquals("512 КБ", StatsFormat.bytes(512L * 1024));
        assertEquals("312 МБ", StatsFormat.bytes(312L * 1024 * 1024));
        assertEquals("1,5 ГБ", StatsFormat.bytes(1536L * 1024 * 1024));
        assertEquals("2,0 ГБ", StatsFormat.bytes(2L * 1024 * 1024 * 1024));
    }

    @Test public void kilobytesAndUnknown() {
        assertEquals("84 МБ", StatsFormat.kilobytes(84L * 1024));
        assertEquals("—", StatsFormat.kilobytes(null));
        assertEquals("—", StatsFormat.bytes(null));
        assertEquals("—", StatsFormat.millis(null));
    }

    @Test public void millisPickTheUnit() {
        assertEquals("0 с", StatsFormat.millis(400L));
        assertEquals("45 с", StatsFormat.millis(45_000L));
        assertEquals("2 мин", StatsFormat.millis(125_000L));
        assertEquals("1 ч", StatsFormat.millis(3_600_000L));
        assertEquals("3 ч 5 мин", StatsFormat.millis((3 * 3600 + 5 * 60) * 1000L));
    }
}
