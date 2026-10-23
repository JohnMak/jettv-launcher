package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FocusDimTest {
    @Test
    public void focusedIsFullAndUnfocusedIsDimmedPerRole() {
        for (FocusDim.Role role : FocusDim.Role.values()) {
            assertEquals(1f, FocusDim.level(role, true), 0f);
        }
        assertEquals(0.8f, FocusDim.level(FocusDim.Role.TEXT, false), 0f);
        assertEquals(0.55f, FocusDim.level(FocusDim.Role.TOP_BAR, false), 0f);
        assertEquals(0.75f, FocusDim.level(FocusDim.Role.AMBIENT, false), 0f);
        assertEquals(0.75f, FocusDim.level(FocusDim.Role.TILE, false), 0f);
    }
}
