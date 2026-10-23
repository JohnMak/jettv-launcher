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
    }

    @Test
    public void setLevelFormatsEveryLevel() {
        for (int v = LampState.MIN_LEVEL; v <= LampState.MAX_LEVEL; v++) {
            assertEquals("gmpfUnit setDlpLumensLevel " + v, GmpfCommands.setLevel(LampLevel.of(v).value()));
        }
    }

    @Test
    public void validationAcceptsOnlyGmpfUnitCommands() {
        assertTrue(GmpfCommands.isValid(GmpfCommands.GET_MODE));
        assertTrue(GmpfCommands.isValid(GmpfCommands.GET_LEVEL));
        assertTrue(GmpfCommands.isValid(GmpfCommands.SET_MODE_CUSTOM));
        for (int v = LampState.MIN_LEVEL; v <= LampState.MAX_LEVEL; v++) {
            assertTrue(GmpfCommands.isValid(GmpfCommands.setLevel(LampLevel.of(v).value())));
        }
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
