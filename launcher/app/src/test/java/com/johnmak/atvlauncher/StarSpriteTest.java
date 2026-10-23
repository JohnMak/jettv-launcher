package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class StarSpriteTest {
    @Test public void footprintNeverBelowMinimum() {
        assertEquals(StarSprite.MIN_FOOTPRINT_PX, StarSprite.footprintPx(0.8f), 0f);
        assertEquals(3.2f, StarSprite.footprintPx(3.2f), 0f);
    }

    @Test public void tinyStarsLoseAlphaByAreaRatioBigOnesKeepIt() {
        assertEquals(1f, StarSprite.alphaScale(1.5f), 1e-6f);
        assertEquals(1f, StarSprite.alphaScale(4f), 1e-6f);
        assertEquals(0.4096f, StarSprite.alphaScale(0.96f), 1e-4f); // (0.96 / 1.5)^2
    }

    @Test public void spriteIsOddSquareSymmetricWithPeakInTheCentre() {
        float sigma = StarSprite.sigmaPx(2.4f);
        int side = StarSprite.side(sigma);
        assertEquals(1, side % 2);
        byte[] a = StarSprite.alpha(sigma);
        assertEquals(side * side, a.length);
        int c = side / 2;
        assertEquals(255, a[c * side + c] & 0xFF);
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                assertEquals(a[i * side + j], a[j * side + i]);
                assertEquals(a[i * side + j], a[(side - 1 - i) * side + (side - 1 - j)]);
                assertTrue((a[i * side + j] & 0xFF) <= (a[c * side + c] & 0xFF));
            }
        }
        // falls off smoothly and nearly to zero at the border
        assertTrue((a[c * side + c + 1] & 0xFF) < 255);
        assertTrue((a[c * side] & 0xFF) < 8);
    }

    @Test public void halfIntensityAtHalfTheFootprint() {
        float footprint = 4f;
        float sigma = StarSprite.sigmaPx(footprint);
        byte[] a = StarSprite.alpha(sigma);
        int side = StarSprite.side(sigma);
        int c = side / 2;
        int atHalfWidth = a[c * side + c + 2] & 0xFF; // 2 px = footprint / 2
        assertEquals(128, atHalfWidth, 4);
    }
}
