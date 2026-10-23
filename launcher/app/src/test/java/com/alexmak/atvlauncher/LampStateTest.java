package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class LampStateTest {
    @Test
    public void readyKeepsEveryValidLevel() {
        for (int level = LampState.MIN_LEVEL; level <= LampState.MAX_LEVEL; level++) {
            LampState s = LampState.ready(level);
            assertEquals(LampState.Kind.READY, s.kind);
            assertEquals(level, s.level);
            assertNull(s.message);
        }
    }

    @Test
    public void readyRejectsOutOfRangeLevels() {
        for (int level : new int[] {0, -1, 11, 100}) {
            try {
                LampState.ready(level);
                fail("level " + level + " must be rejected");
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
