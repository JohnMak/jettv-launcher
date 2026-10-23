package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SystemScreensaverTest {
    private static final String OWN = "com.johnmak.atvlauncher";
    private static final String DREAM = OWN + "/.StarfieldDream";

    @Test
    public void danglingOldComponentIsRewrittenToOurs() {
        List<SystemScreensaver.Write> w = SystemScreensaver.plan("com.old.app/.OldDream", "1", OWN, 5);
        assertEquals(1, w.size());
        assertEquals("screensaver_components", w.get(0).key);
        assertEquals(DREAM, w.get(0).value);
    }

    @Test
    public void missingValuesAreWritten() {
        List<SystemScreensaver.Write> w = SystemScreensaver.plan(null, null, OWN, 5);
        assertEquals(2, w.size());
        assertEquals("screensaver_enabled", w.get(1).key);
        assertEquals("1", w.get(1).value);
    }

    @Test
    public void delayZeroDisablesButKeepsOurComponent() {
        List<SystemScreensaver.Write> w = SystemScreensaver.plan("com.old.app/.OldDream", "1", OWN, 0);
        assertEquals(2, w.size());
        assertEquals(DREAM, w.get(0).value);
        assertEquals("screensaver_enabled", w.get(1).key);
        assertEquals("0", w.get(1).value);
    }

    @Test
    public void alreadyCorrectWritesNothing() {
        assertTrue(SystemScreensaver.plan(DREAM, "1", OWN, 5).isEmpty());
        assertTrue(SystemScreensaver.plan(DREAM, "0", OWN, 0).isEmpty());
    }
}
