package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackdropVignetteTest {
    @Test
    public void centreIsDarkerThanEdge() {
        assertEquals(0.25f, BackdropVignette.CENTRE_BRIGHTNESS, 1e-6f);
        assertEquals(0.60f, BackdropVignette.EDGE_BRIGHTNESS, 1e-6f);
        assertTrue(BackdropVignette.CENTRE_BRIGHTNESS < BackdropVignette.EDGE_BRIGHTNESS);
    }

    @Test
    public void stopsAreIncreasingAndEndAtTheEdge() {
        float[] pos = BackdropVignette.positions();
        assertEquals(0f, pos[0], 0f);
        assertEquals(1f, pos[pos.length - 1], 0f);
        for (int i = 1; i < pos.length; i++) assertTrue(pos[i] > pos[i - 1]);
    }

    @Test
    public void overlayAlphaFallsMonotonicallyFromCentreToEdge() {
        int[] alpha = BackdropVignette.alphas();
        assertEquals(BackdropVignette.positions().length, alpha.length);
        assertEquals(Math.round(255 * 0.75f), alpha[0]);
        assertEquals(Math.round(255 * 0.40f), alpha[alpha.length - 1]);
        for (int i = 1; i < alpha.length; i++) assertTrue(alpha[i] <= alpha[i - 1]);
    }
}
