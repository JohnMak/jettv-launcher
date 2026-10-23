package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class CleanupLogTest {
    @Test public void recordsRoundTripThroughSerializer() {
        CleanupLog log = CleanupLog.empty();
        assertTrue(log.entries().isEmpty());
        log = log.record("a.b", CleanupProfile.Action.DISABLE, 1000L)
                 .record("c.d", CleanupProfile.Action.REMOVE, 2000L)
                 .record("e.f", CleanupProfile.Action.RESET, 3000L);
        CleanupLog back = CleanupLog.parse(log.serialize()).value();
        assertEquals(3, back.entries().size());
        assertEquals(CleanupProfile.Action.REMOVE, back.get("c.d").action);
        assertEquals(2000L, back.get("c.d").timestampMillis);
        assertEquals("a.b", back.entries().get(0).pkg);
        assertNull(back.get("zz.zz"));
    }

    @Test public void rerecordReplacesAndForgetDrops() {
        CleanupLog log = CleanupLog.empty().record("a.b", CleanupProfile.Action.DISABLE, 1L)
                .record("a.b", CleanupProfile.Action.REMOVE, 2L);
        assertEquals(1, log.entries().size());
        assertEquals(CleanupProfile.Action.REMOVE, log.get("a.b").action);
        assertTrue(log.forget("a.b").entries().isEmpty());
        assertEquals(1, log.entries().size());
    }

    @Test public void corruptTextIsRejected() {
        assertFalse(CleanupLog.parse("disable\ta.b\n").isOk());
        assertFalse(CleanupLog.parse("wipe\ta.b\t1\n").isOk());
        assertFalse(CleanupLog.parse("disable\tbad pkg\t1\n").isOk());
        assertFalse(CleanupLog.parse("disable\ta.b\tx\n").isOk());
        assertTrue(CleanupLog.parse("").value().entries().isEmpty());
    }

    @Test public void loadsAndSavesThroughStorage() {
        final String[] cell = {""};
        CleanupLog.Storage s = new CleanupLog.Storage() {
            public String load() { return cell[0]; }
            public void save(String text) { cell[0] = text; }
        };
        CleanupLog.empty().record("a.b", CleanupProfile.Action.DISABLE, 5L).saveTo(s);
        assertEquals(5L, CleanupLog.loadFrom(s).value().get("a.b").timestampMillis);
    }
}
