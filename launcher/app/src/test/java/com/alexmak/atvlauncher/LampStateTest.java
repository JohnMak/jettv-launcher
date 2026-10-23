package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class LampStateTest {
    @Test
    public void readyKeepsEveryValidPosition() {
        for (int position = BrightnessScale.MIN_POSITION; position <= BrightnessScale.MAX_POSITION; position++) {
            LampState s = LampState.ready(position);
            assertEquals(LampState.Kind.READY, s.kind);
            assertEquals(position, s.position);
            assertNull(s.message);
        }
    }

    @Test
    public void readyRejectsOutOfRangePositions() {
        for (int position : new int[] {-1, 18, 100}) {
            try {
                LampState.ready(position);
                fail("position " + position + " must be rejected");
            } catch (IllegalArgumentException expected) {
                // invariant enforced
            }
        }
    }

    @Test
    public void errorKeepsMessageAndLoadingIsEmpty() {
        LampState e = LampState.error("Подтвердите доступ ADB на экране");
        assertEquals(LampState.Kind.ERROR, e.kind);
        assertEquals("Подтвердите доступ ADB на экране", e.message);
        LampState l = LampState.loading();
        assertEquals(LampState.Kind.LOADING, l.kind);
        assertNull(l.message);
    }
}
