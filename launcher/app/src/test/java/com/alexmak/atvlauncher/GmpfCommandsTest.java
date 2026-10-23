package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GmpfCommandsTest {
    @Test
    public void fixedCommandsMatchWhitelist() {
        assertEquals("gmpfUnit getDlpLumensMode", GmpfCommands.GET_MODE);
        assertEquals("gmpfUnit getDlpLumensLevel", GmpfCommands.GET_LEVEL);
        assertEquals("gmpfUnit setDlpLumensMode 6", GmpfCommands.SET_MODE_CUSTOM);
        assertEquals(6, GmpfCommands.MODE_CUSTOM);
        assertEquals("gmpfUnit getBrightness 0", GmpfCommands.GET_BRIGHTNESS);
        assertEquals("gmpfUnit getContrast 0", GmpfCommands.GET_CONTRAST);
    }

    @Test
    public void pictureSettersFormatAndRejectOutOfRange() {
        assertEquals("gmpfUnit setBrightness 0 0", GmpfCommands.setBrightness(0));
        assertEquals("gmpfUnit setBrightness 0 57", GmpfCommands.setBrightness(57));
        assertEquals("gmpfUnit setContrast 0 100", GmpfCommands.setContrast(100));
        for (int bad : new int[] {-1, 101}) {
            try {
                GmpfCommands.setBrightness(bad);
                org.junit.Assert.fail("brightness " + bad);
            } catch (IllegalArgumentException expected) {
                // invariant enforced
            }
            try {
                GmpfCommands.setContrast(bad);
                org.junit.Assert.fail("contrast " + bad);
            } catch (IllegalArgumentException expected) {
                // invariant enforced
            }
        }
    }

    @Test
    public void setLevelFormatsEveryLevel() {
        for (int v = LampLevel.MIN_LEVEL; v <= LampLevel.MAX_LEVEL; v++) {
            assertEquals("gmpfUnit setDlpLumensLevel " + v, GmpfCommands.setLevel(LampLevel.of(v).value()));
        }
    }

    @Test
    public void validationAcceptsOnlyGmpfUnitCommands() {
        assertTrue(GmpfCommands.isValid(GmpfCommands.GET_MODE));
        assertTrue(GmpfCommands.isValid(GmpfCommands.GET_LEVEL));
        assertTrue(GmpfCommands.isValid(GmpfCommands.SET_MODE_CUSTOM));
        for (int v = LampLevel.MIN_LEVEL; v <= LampLevel.MAX_LEVEL; v++) {
            assertTrue(GmpfCommands.isValid(GmpfCommands.setLevel(LampLevel.of(v).value())));
        }
        assertTrue(GmpfCommands.isValid(GmpfCommands.GET_BRIGHTNESS));
        assertTrue(GmpfCommands.isValid(GmpfCommands.GET_CONTRAST));
        assertTrue(GmpfCommands.isValid(GmpfCommands.setBrightness(42)));
        assertTrue(GmpfCommands.isValid(GmpfCommands.setContrast(100)));
        assertFalse(GmpfCommands.isValid("gmpfUnit setBrightness 1 42"));
        assertFalse(GmpfCommands.isValid("gmpfUnit setBrightness 0 42 7"));
        assertFalse(GmpfCommands.isValid("gmpfUnit setBrightness 0 $(id)"));
        assertFalse(GmpfCommands.isValid(null));
        assertFalse(GmpfCommands.isValid(""));
        assertFalse(GmpfCommands.isValid("reboot"));
        assertFalse(GmpfCommands.isValid("gmpfUnit"));
        assertFalse(GmpfCommands.isValid("gmpfUnitX getDlpLumensMode"));
        assertFalse(GmpfCommands.isValid("gmpfUnit getDlpLumensMode; reboot"));
        assertFalse(GmpfCommands.isValid("gmpfUnit getDlpLumensMode\nreboot"));
        assertFalse(GmpfCommands.isValid("gmpfUnit setDlpLumensLevel $(id)"));
    }
}
