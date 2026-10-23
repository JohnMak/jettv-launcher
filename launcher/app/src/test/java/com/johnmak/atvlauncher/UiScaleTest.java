package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UiScaleTest {
    @Test public void valuesAreSeventyToOneThirtyInFiveSteps() {
        assertEquals(13, UiScale.PERCENTS.length);
        assertEquals(70, UiScale.PERCENTS[0]);
        assertEquals(130, UiScale.PERCENTS[UiScale.PERCENTS.length - 1]);
        for (int i = 1; i < UiScale.PERCENTS.length; i++) assertEquals(5, UiScale.PERCENTS[i] - UiScale.PERCENTS[i - 1]);
        assertEquals(100, UiScale.DEFAULT_PERCENT);
        assertTrue(UiScale.isValid(100));
    }

    @Test public void onlyListedValuesAreValid() {
        assertTrue(UiScale.isValid(70));
        assertTrue(UiScale.isValid(130));
        assertTrue(UiScale.isValid(85));
        assertEquals(false, UiScale.isValid(0));
        assertEquals(false, UiScale.isValid(66));
        assertEquals(false, UiScale.isValid(135));
        assertEquals(false, UiScale.isValid(-100));
    }

    @Test public void storedValueOutsideTheListBecomesTheDefault() {
        assertEquals(85, UiScale.fromStored(85));
        assertEquals(100, UiScale.fromStored(0));
        assertEquals(100, UiScale.fromStored(102));
        assertEquals(100, UiScale.fromStored(500));
        assertEquals(100, UiScale.fromStored(-70));
    }

    @Test public void nextAndPrevStepByFiveAndStopAtTheEnds() {
        assertEquals(105, UiScale.next(100));
        assertEquals(95, UiScale.prev(100));
        assertEquals(130, UiScale.next(130));
        assertEquals(70, UiScale.prev(70));
        assertEquals(130, UiScale.next(125));
        assertEquals(75, UiScale.prev(80));
    }

    @Test public void densityDpiIsTheBaseTimesPercentRounded() {
        assertEquals(320, UiScale.densityDpi(320, 100));
        assertEquals(224, UiScale.densityDpi(320, 70));
        assertEquals(416, UiScale.densityDpi(320, 130));
        assertEquals(272, UiScale.densityDpi(320, 85));
        assertEquals(120, UiScale.densityDpi(160, 75)); // 120.0
        assertEquals(213, UiScale.densityDpi(213, 100));
        assertEquals(149, UiScale.densityDpi(213, 70)); // 149.1
        assertEquals(160, UiScale.densityDpi(213, 75)); // 159.75
    }
}
