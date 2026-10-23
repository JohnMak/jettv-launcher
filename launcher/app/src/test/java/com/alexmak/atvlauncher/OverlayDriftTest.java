package com.alexmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class OverlayDriftTest {
    @Test
    public void offsetsStayNearCentreAndMoveByMinimumStep() {
        OverlayDrift d = new OverlayDrift(5L);
        assertTrue(Math.abs(d.x()) <= OverlayDrift.MAX_OFFSET);
        assertTrue(Math.abs(d.y()) <= OverlayDrift.MAX_OFFSET);
        float px = d.x(), py = d.y();
        for (int i = 0; i < 2000; i++) {
            d.next();
            assertTrue(Math.abs(d.x()) <= OverlayDrift.MAX_OFFSET + 1e-6f);
            assertTrue(Math.abs(d.y()) <= OverlayDrift.MAX_OFFSET + 1e-6f);
            double dist = Math.hypot(d.x() - px, d.y() - py);
            assertTrue("step " + dist, dist >= OverlayDrift.MIN_STEP - 1e-6);
            px = d.x();
            py = d.y();
        }
    }

    @Test
    public void deterministicForSameSeed() {
        OverlayDrift a = new OverlayDrift(9L);
        OverlayDrift b = new OverlayDrift(9L);
        for (int i = 0; i < 20; i++) {
            a.next(); b.next();
            assertEquals(a.x(), b.x(), 0f);
            assertEquals(a.y(), b.y(), 0f);
        }
    }
}
