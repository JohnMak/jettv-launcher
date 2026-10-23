package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionTest {
    @Test public void animationsOnKeepsEverythingAnimated() {
        Motion m = Motion.of(LauncherSettings.DEFAULTS.withAnimationsOff(false));
        assertTrue(m.focusAnimated(true));
        assertFalse(m.focusAnimated(false));
        assertTrue(m.scrollAnimated());
    }

    @Test public void animationsOffMakesSelectionInstantButKeepsScrolling() {
        Motion m = Motion.of(LauncherSettings.DEFAULTS.withAnimationsOff(true));
        assertFalse(m.focusAnimated(true));
        assertFalse(m.focusAnimated(false));
        assertTrue(m.scrollAnimated());
    }
}
