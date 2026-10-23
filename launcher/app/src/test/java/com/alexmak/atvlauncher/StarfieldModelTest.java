package com.alexmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class StarfieldModelTest {
    @Test
    public void respawnsAtFarPlaneAndStaysInRange() {
        StarfieldModel m = new StarfieldModel(300, 1L, StarfieldModel.DEFAULT_SPEED);
        boolean respawned = false;
        for (int step = 0; step < 100 * 60; step++) { // 100 s at 60 steps/s
            float[] before = new float[m.count()];
            for (int i = 0; i < m.count(); i++) before[i] = m.depth(i);
            m.step(1f / 60f);
            for (int i = 0; i < m.count(); i++) {
                float z = m.depth(i);
                assertTrue(z > 0f && z <= 1f);
                assertTrue(z >= StarfieldModel.MIN_Z);
                if (z > before[i]) {
                    respawned = true;
                    assertEquals(1f, z, 0f);
                }
            }
        }
        assertTrue(respawned);
    }

    @Test
    public void projectionStaysNearScreenAndBrightnessIsCapped() {
        StarfieldModel m = new StarfieldModel(300, 2L, StarfieldModel.DEFAULT_SPEED);
        int w = 1920, h = 1080;
        for (int step = 0; step < 3000; step++) {
            m.step(0.033f);
            for (int i = 0; i < m.count(); i++) {
                assertTrue(m.screenX(i, w) >= -0.06f * w && m.screenX(i, w) <= 1.06f * w);
                assertTrue(m.screenY(i, h) >= -0.06f * h && m.screenY(i, h) <= 1.06f * h);
                assertTrue(m.brightness(i) <= StarfieldModel.MAX_BRIGHTNESS + 1e-6f);
                assertTrue(m.brightness(i) >= StarfieldModel.MIN_BRIGHTNESS - 1e-6f);
                assertTrue(m.sizeClass(i) >= 0 && m.sizeClass(i) < StarfieldModel.SIZE_CLASSES);
                assertTrue(m.colorClass(i) >= 0 && m.colorClass(i) < StarfieldModel.COLOR_CLASSES);
                assertTrue(m.brightnessLevel(i) >= 0 && m.brightnessLevel(i) < StarfieldModel.BRIGHTNESS_LEVELS);
            }
        }
    }

    @Test
    public void nearerIsBrighter() {
        StarfieldModel m = new StarfieldModel(2, 3L, StarfieldModel.DEFAULT_SPEED);
        int near = m.depth(0) < m.depth(1) ? 0 : 1;
        int far = 1 - near;
        assertTrue(m.brightness(near) > m.brightness(far));
        assertTrue(StarfieldModel.levelBrightness(StarfieldModel.BRIGHTNESS_LEVELS - 1)
                <= StarfieldModel.MAX_BRIGHTNESS);
        assertTrue(StarfieldModel.levelBrightness(0) >= StarfieldModel.MIN_BRIGHTNESS);
    }

    @Test
    public void deterministicForSameSeed() {
        StarfieldModel a = new StarfieldModel(50, 7L, StarfieldModel.DEFAULT_SPEED);
        StarfieldModel b = new StarfieldModel(50, 7L, StarfieldModel.DEFAULT_SPEED);
        for (int s = 0; s < 500; s++) { a.step(0.05f); b.step(0.05f); }
        for (int i = 0; i < 50; i++) {
            assertEquals(a.screenX(i, 1000), b.screenX(i, 1000), 0f);
            assertEquals(a.depth(i), b.depth(i), 0f);
            assertEquals(a.sizeClass(i), b.sizeClass(i));
            assertEquals(a.colorClass(i), b.colorClass(i));
        }
    }

    @Test
    public void crossingTakesAboutThirtySeconds() {
        StarfieldModel m = new StarfieldModel(1, 4L, StarfieldModel.DEFAULT_SPEED);
        float t = (1f - StarfieldModel.MIN_Z) / StarfieldModel.DEFAULT_SPEED;
        assertTrue(t >= 20f && t <= 40f);
    }

    @Test
    public void defaultStarCountIsDense() {
        assertTrue(StarfieldModel.DEFAULT_STAR_COUNT >= 1500 && StarfieldModel.DEFAULT_STAR_COUNT <= 2500);
    }

    @Test
    public void sizesAreMostlyTinyAndColoursMostlyWhite() {
        StarfieldModel m = new StarfieldModel(20000, 11L, StarfieldModel.DEFAULT_SPEED);
        int[] sizes = new int[StarfieldModel.SIZE_CLASSES];
        int[] colors = new int[StarfieldModel.COLOR_CLASSES];
        for (int i = 0; i < m.count(); i++) { sizes[m.sizeClass(i)]++; colors[m.colorClass(i)]++; }
        int n = m.count();
        assertTrue(sizes[0] > 0.7 * n);
        for (int k = 1; k < sizes.length; k++) {
            assertTrue(sizes[k] > 0);
            assertTrue(sizes[k] < sizes[k - 1]);
        }
        assertTrue(colors[0] > 0.6 * n);
        for (int k = 1; k < colors.length; k++) assertTrue(colors[k] > 0 && colors[k] < 0.2 * n);
        assertTrue(colors[3] < colors[1]);
    }

    @Test
    public void spawnsAreClusteredAroundCentres() {
        // Cell-occupancy test: clustered stars concentrate in far fewer 10x10 grid cells than uniform.
        StarfieldModel m = new StarfieldModel(20000, 12L, StarfieldModel.DEFAULT_SPEED);
        int[] cells = new int[100];
        for (int i = 0; i < m.count(); i++) {
            float sx = (m.screenX(i, 1000) - 500f) * m.depth(i) / 500f; // = x
            float sy = (m.screenY(i, 1000) - 500f) * m.depth(i) / 500f; // = y
            int cx = Math.max(0, Math.min(9, (int) ((sx + 1f) * 5f)));
            int cy = Math.max(0, Math.min(9, (int) ((sy + 1f) * 5f)));
            cells[cy * 10 + cx]++;
        }
        java.util.Arrays.sort(cells);
        int top10 = 0;
        for (int k = 90; k < 100; k++) top10 += cells[k];
        // Uniform would put ~10% in the top 10 cells; clusters put over 25%.
        assertTrue(top10 > 0.25 * m.count());
        assertTrue(cells[0] > 0); // background population still reaches sparse regions
    }

    @Test
    public void clusterCentresStayBoundedWhileStepping() {
        StarfieldModel m = new StarfieldModel(500, 13L, StarfieldModel.DEFAULT_SPEED);
        for (int s = 0; s < 20000; s++) m.step(0.5f); // 10000 s
        for (int i = 0; i < m.count(); i++) {
            assertTrue(m.depth(i) > 0f && m.depth(i) <= 1f);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmpty() {
        new StarfieldModel(0, 1L, 0.1f);
    }
}
