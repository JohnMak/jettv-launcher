package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrightnessScaleTest {
    private static BrightnessScale.Position pos(int v) { return BrightnessScale.Position.of(v).value(); }

    private static LampLevel lamp(int v) { return LampLevel.of(v).value(); }

    @Test public void positionRangeIsValidatedAndClamped() {
        for (int v = 0; v <= 17; v++) {
            assertTrue(BrightnessScale.Position.of(v).isOk());
            assertEquals(v, pos(v).value());
        }
        for (int v : new int[] {-1, 18, Integer.MAX_VALUE}) {
            assertFalse(BrightnessScale.Position.of(v).isOk());
        }
        assertTrue(BrightnessScale.Position.of(18).error().contains("18"));
        assertEquals(0, pos(0).step(-1).value());
        assertEquals(17, pos(17).step(1).value());
        assertEquals(7, pos(6).step(1).value());
        assertEquals(5, pos(6).step(-1).value());
        assertEquals(17, pos(3).step(1000).value());
        assertEquals(0, pos(3).step(Integer.MIN_VALUE).value());
    }

    @Test public void allEighteenPositionsMapToLampAndPicture() {
        int[] lamps = {1, 1, 1, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10, 10, 10};
        int[] pictures = {20, 27, 35, 42, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 57, 65, 72, 80};
        for (int i = 0; i < 18; i++) {
            assertEquals("lamp F" + i, lamps[i], BrightnessScale.lamp(pos(i)).value());
            assertEquals("picture F" + i, pictures[i], BrightnessScale.picture(pos(i)));
        }
    }

    @Test public void deviceValuesRoundTripEveryPosition() {
        for (int p = 0; p <= 17; p++) {
            int pic = BrightnessScale.picture(pos(p));
            assertEquals(pos(p), BrightnessScale.fromDevice(BrightnessScale.lamp(pos(p)), pic, pic));
        }
    }

    @Test public void offGridDeviceValuesMapByLampOrNearestZone() {
        assertEquals(pos(13), BrightnessScale.fromDevice(lamp(10), 50, 30)); // not above neutral: by lamp
        assertEquals(pos(8), BrightnessScale.fromDevice(lamp(5), 90, 10));   // mid lamp ignores picture
        assertEquals(pos(4), BrightnessScale.fromDevice(lamp(1), 50, 50));
        assertEquals(pos(4), BrightnessScale.fromDevice(lamp(1), 80, 60));   // above neutral at lamp 1: by lamp
        assertEquals(pos(13), BrightnessScale.fromDevice(lamp(10), 20, 20)); // below neutral at lamp 10: by lamp
        assertEquals(pos(0), BrightnessScale.fromDevice(lamp(1), 0, 0));
        assertEquals(pos(0), BrightnessScale.fromDevice(lamp(1), 22, 24));
        assertEquals(pos(2), BrightnessScale.fromDevice(lamp(1), 33, 34));
        assertEquals(pos(3), BrightnessScale.fromDevice(lamp(1), 49, 49));
        assertEquals(pos(14), BrightnessScale.fromDevice(lamp(10), 51, 52));
        assertEquals(pos(16), BrightnessScale.fromDevice(lamp(10), 70, 71));
        assertEquals(pos(17), BrightnessScale.fromDevice(lamp(10), 100, 100));
    }
}
