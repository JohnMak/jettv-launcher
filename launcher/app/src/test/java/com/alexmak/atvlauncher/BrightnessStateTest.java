package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class BrightnessStateTest {
    @Test
    public void readyKeepsEveryValidPosition() {
        for (int position = BrightnessScale.MIN_POSITION; position <= BrightnessScale.MAX_POSITION; position++) {
            BrightnessState s = BrightnessState.ready(position);
            assertEquals(BrightnessState.Kind.READY, s.kind);
            assertEquals(position, s.position);
            assertNull(s.error);
        }
    }

    @Test
    public void readyRejectsOutOfRangePositions() {
        for (int position : new int[] {-1, 16, 100}) {
            try {
                BrightnessState.ready(position);
                fail("position " + position + " must be rejected");
            } catch (IllegalArgumentException expected) {
                // invariant enforced
            }
        }
    }

    @Test
    public void errorKeepsKindAndLoadingIsEmpty() {
        BrightnessState e = BrightnessState.error(ShellError.UNAUTHORIZED);
        assertEquals(BrightnessState.Kind.ERROR, e.kind);
        assertEquals(ShellError.UNAUTHORIZED, e.error);
        try {
            BrightnessState.error(null);
            fail("null error must be rejected");
        } catch (IllegalArgumentException expected) {
            // invariant enforced
        }
        BrightnessState l = BrightnessState.loading();
        assertEquals(BrightnessState.Kind.LOADING, l.kind);
        assertNull(l.error);
    }
}
