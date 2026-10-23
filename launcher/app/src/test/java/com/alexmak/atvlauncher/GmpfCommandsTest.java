package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;

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
}
