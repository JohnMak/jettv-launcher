package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrightnessKeyModelTest {
    private static BrightnessKeyModel.Action key(BrightnessKeyModel m, BrightnessKeyModel.Key k) {
        return m.onKey(k);
    }

    @Test
    public void focusChangesShowAndHideOnce() {
        BrightnessKeyModel m = new BrightnessKeyModel();
        assertSame(BrightnessKeyModel.Action.PASS, m.onFocus(false));
        assertSame(BrightnessKeyModel.Action.SHOW, m.onFocus(true));
        assertSame(BrightnessKeyModel.Action.PASS, m.onFocus(true));
        assertSame(BrightnessKeyModel.Action.HIDE, m.onFocus(false));
        assertSame(BrightnessKeyModel.Action.PASS, m.onFocus(false));
    }

    @Test
    public void keysPassWhenSunNotFocused() {
        BrightnessKeyModel m = new BrightnessKeyModel();
        for (BrightnessKeyModel.Key k : BrightnessKeyModel.Key.values()) {
            assertSame(BrightnessKeyModel.Action.PASS, key(m, k));
        }
        assertFalse(m.isAdjusting());
    }

    @Test
    public void outsideAdjustOnlyOkIsHandled() {
        BrightnessKeyModel m = new BrightnessKeyModel();
        m.onFocus(true);
        for (BrightnessKeyModel.Key k : new BrightnessKeyModel.Key[]{
                BrightnessKeyModel.Key.BACK, BrightnessKeyModel.Key.LEFT, BrightnessKeyModel.Key.RIGHT,
                BrightnessKeyModel.Key.UP, BrightnessKeyModel.Key.DOWN}) {
            assertSame(BrightnessKeyModel.Action.PASS, key(m, k));
        }
        assertFalse(m.isAdjusting());
        assertSame(BrightnessKeyModel.Action.ENTER_ADJUST, key(m, BrightnessKeyModel.Key.OK));
        assertTrue(m.isAdjusting());
    }

    @Test
    public void adjustModeStepsConsumesAndExits() {
        BrightnessKeyModel m = new BrightnessKeyModel();
        m.onFocus(true);
        m.onKey(BrightnessKeyModel.Key.OK);
        BrightnessKeyModel.Action left = key(m, BrightnessKeyModel.Key.LEFT);
        assertEquals(BrightnessKeyModel.Kind.STEP, left.kind);
        assertEquals(-1, left.delta);
        BrightnessKeyModel.Action right = key(m, BrightnessKeyModel.Key.RIGHT);
        assertEquals(BrightnessKeyModel.Kind.STEP, right.kind);
        assertEquals(1, right.delta);
        assertSame(BrightnessKeyModel.Action.CONSUME, key(m, BrightnessKeyModel.Key.UP));
        assertSame(BrightnessKeyModel.Action.CONSUME, key(m, BrightnessKeyModel.Key.DOWN));
        assertTrue(m.isAdjusting());

        assertSame(BrightnessKeyModel.Action.EXIT_ADJUST, key(m, BrightnessKeyModel.Key.OK));
        assertFalse(m.isAdjusting());
        m.onKey(BrightnessKeyModel.Key.OK);
        assertSame(BrightnessKeyModel.Action.EXIT_ADJUST, key(m, BrightnessKeyModel.Key.BACK));
        assertFalse(m.isAdjusting());
        // popup stays: no HIDE, focus is still on the sun, and arrows pass again
        assertSame(BrightnessKeyModel.Action.PASS, key(m, BrightnessKeyModel.Key.LEFT));
    }

    @Test
    public void focusLossEndsAdjustAndHides() {
        BrightnessKeyModel m = new BrightnessKeyModel();
        m.onFocus(true);
        m.onKey(BrightnessKeyModel.Key.OK);
        assertSame(BrightnessKeyModel.Action.HIDE, m.onFocus(false));
        assertFalse(m.isAdjusting());
        assertSame(BrightnessKeyModel.Action.PASS, key(m, BrightnessKeyModel.Key.OK));
        m.onFocus(true);
        assertFalse(m.isAdjusting());
        assertSame(BrightnessKeyModel.Action.ENTER_ADJUST, key(m, BrightnessKeyModel.Key.OK));
    }
}
