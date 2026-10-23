package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionTest {
    @Test public void animationsOnKeepsEverythingAnimated() {
        Motion m = Motion.of(LauncherSettings.DEFAULTS.withAnimationsOff(false));
        assertTrue(m.focusAnimated(true));
        assertFalse(m.focusAnimated(false));
        assertTrue(m.crossfade());
        assertTrue(m.scrollAnimated());
    }

    @Test public void animationsOffMakesFocusAndBackdropInstantButKeepsScrolling() {
        Motion m = Motion.of(LauncherSettings.DEFAULTS.withAnimationsOff(true));
        assertFalse(m.focusAnimated(true));
        assertFalse(m.focusAnimated(false));
        assertFalse(m.crossfade());
        assertTrue(m.scrollAnimated());
    }
}
