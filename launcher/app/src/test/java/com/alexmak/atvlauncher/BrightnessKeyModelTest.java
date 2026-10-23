package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class BrightnessKeyModelTest {
    private static final List<BrightnessKeyModel.Action> PASS = Collections.singletonList(BrightnessKeyModel.Action.PASS);

    private static List<BrightnessKeyModel.Action> key(BrightnessKeyModel m, BrightnessKeyModel.Key k) {
        return m.onKey(k);
    }

    private static BrightnessKeyModel focused() {
        BrightnessKeyModel m = new BrightnessKeyModel();
        m.onFocus(true);
        return m;
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
            assertEquals(PASS, key(m, k));
        }
    }

    @Test
    public void outsideAdjustOnlyDownIsHandled() {
        BrightnessKeyModel m = focused();
        for (BrightnessKeyModel.Key k : new BrightnessKeyModel.Key[]{
                BrightnessKeyModel.Key.BACK, BrightnessKeyModel.Key.LEFT, BrightnessKeyModel.Key.RIGHT,
                BrightnessKeyModel.Key.UP}) {
            assertEquals(PASS, key(m, k));
        }
        assertEquals(Collections.singletonList(BrightnessKeyModel.Action.ENTER_ADJUST),
                key(m, BrightnessKeyModel.Key.DOWN));
    }

    @Test
    public void adjustModeSteps() {
        BrightnessKeyModel m = focused();
        m.onKey(BrightnessKeyModel.Key.DOWN);
        List<BrightnessKeyModel.Action> left = key(m, BrightnessKeyModel.Key.LEFT);
        assertEquals(1, left.size());
        assertEquals(BrightnessKeyModel.Kind.STEP, left.get(0).kind);
        assertEquals(-1, left.get(0).delta);
        List<BrightnessKeyModel.Action> right = key(m, BrightnessKeyModel.Key.RIGHT);
        assertEquals(1, right.size());
        assertEquals(BrightnessKeyModel.Kind.STEP, right.get(0).kind);
        assertEquals(1, right.get(0).delta);
    }

    @Test
    public void upAndBackExitAdjustKeepingPopupAndFocus() {
        for (BrightnessKeyModel.Key k : new BrightnessKeyModel.Key[]{
                BrightnessKeyModel.Key.UP, BrightnessKeyModel.Key.BACK}) {
            BrightnessKeyModel m = focused();
            m.onKey(BrightnessKeyModel.Key.DOWN);
            assertEquals(Collections.singletonList(BrightnessKeyModel.Action.EXIT_ADJUST), key(m, k));
            // no HIDE, focus is still on the sun, and arrows pass again
            assertEquals(PASS, key(m, BrightnessKeyModel.Key.LEFT));
            assertSame(BrightnessKeyModel.Action.PASS, m.onFocus(true));
        }
    }

    @Test
    public void downWhileAdjustingExitsAndPassesAndStaysSyncedWithRealFocus() {
        // focus move succeeded: the sync after the key reports the sun lost, which hides the popup
        BrightnessKeyModel m = focused();
        m.onKey(BrightnessKeyModel.Key.DOWN);
        assertEquals(Arrays.asList(BrightnessKeyModel.Action.EXIT_ADJUST, BrightnessKeyModel.Action.PASS),
                key(m, BrightnessKeyModel.Key.DOWN));
        assertSame(BrightnessKeyModel.Action.HIDE, m.onFocus(false));
        assertEquals(PASS, key(m, BrightnessKeyModel.Key.DOWN));
        assertSame(BrightnessKeyModel.Action.SHOW, m.onFocus(true));

        // focus move failed (no zone below): the sun is still focused, model and popup stay in sync
        m = focused();
        m.onKey(BrightnessKeyModel.Key.DOWN);
        key(m, BrightnessKeyModel.Key.DOWN);
        assertSame(BrightnessKeyModel.Action.PASS, m.onFocus(true));
        assertEquals(Collections.singletonList(BrightnessKeyModel.Action.ENTER_ADJUST),
                key(m, BrightnessKeyModel.Key.DOWN)); // the sun still owns DOWN
    }

    @Test
    public void focusLossEndsAdjustAndHides() {
        BrightnessKeyModel m = focused();
        m.onKey(BrightnessKeyModel.Key.DOWN);
        assertSame(BrightnessKeyModel.Action.HIDE, m.onFocus(false));
        assertEquals(PASS, key(m, BrightnessKeyModel.Key.DOWN));
        m.onFocus(true);
        assertEquals(Collections.singletonList(BrightnessKeyModel.Action.ENTER_ADJUST),
                key(m, BrightnessKeyModel.Key.DOWN));
    }
}
