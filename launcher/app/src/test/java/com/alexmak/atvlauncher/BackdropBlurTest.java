package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackdropBlurTest {
    @Test
    public void backdropBitmapsAreFullColour() {
        assertEquals(android.graphics.Bitmap.Config.ARGB_8888, BackdropBlur.CONFIG);
    }

    @Test
    public void uniformImageIsOnlyDimmed() {
        int[] px = new int[8 * 6];
        java.util.Arrays.fill(px, 0xFFC8C8C8);
        BackdropBlur.blurAndDim(px, 8, 6, 2, 3, 0.5f);
        for (int p : px) assertEquals(0xFF646464, p);
    }

    @Test
    public void brightPixelSpreadsToNeighboursAndLosesPeak() {
        int w = 9;
        int h = 9;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF000000);
        px[4 * w + 4] = 0xFFFFFFFF;
        BackdropBlur.blurAndDim(px, w, h, 2, 3, 1f);
        int centre = px[4 * w + 4] & 0xFF;
        int neighbour = px[4 * w + 5] & 0xFF;
        assertTrue(neighbour > 0);
        assertTrue(centre < 255);
        assertTrue(centre >= neighbour);
        assertEquals(0xFF, px[0] >>> 24);
    }

    @Test
    public void moreBlurSpreadsFurther() {
        int w = 41;
        int[] one = new int[w];
        int[] three = new int[w];
        java.util.Arrays.fill(one, 0xFF000000);
        java.util.Arrays.fill(three, 0xFF000000);
        one[20] = 0xFFFFFFFF;
        three[20] = 0xFFFFFFFF;
        BackdropBlur.blurAndDim(one, w, 1, 2, 1, 1f);
        BackdropBlur.blurAndDim(three, w, 1, 2, 3, 1f);
        // two radius-2 passes reach 6 px from the peak, one pass only 2 px
        assertEquals(0xFF000000, one[24]);
        assertTrue((three[24] & 0xFF) > 0);
    }

    @Test
    public void halvingNeverJumpsMoreThanHalfAndLandsOnTarget() {
        java.util.List<int[]> steps = BackdropBlur.halvingSteps(1920, 1080, 160, 90);
        assertEquals(4, steps.size()); // 960x540, 480x270, 240x135, 160x90
        int pw = 1920;
        int ph = 1080;
        for (int[] s : steps) {
            assertTrue(s[0] * 2 >= pw && s[1] * 2 >= ph);
            pw = s[0];
            ph = s[1];
        }
        assertEquals(160, pw);
        assertEquals(90, ph);
        assertEquals(1, BackdropBlur.halvingSteps(100, 56, 160, 90).size());
        assertEquals(160, BackdropBlur.halvingSteps(100, 56, 160, 90).get(0)[0]);
    }
}
