package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GlowColorTest {

    private static final float EPS = 0.02f;

    private static float[] hslOf(int rgb) {
        float[] hsl = new float[3];
        GlowColor.toHsl(rgb, hsl);
        return hsl;
    }

    @Test
    public void glowIsAlwaysOpaque() {
        assertEquals(0xFF, GlowColor.glowFor(0xFF0000) >>> 24);
        assertEquals(0xFF, GlowColor.glowFor(0x808080) >>> 24);
        assertEquals(0xFF000000, GlowColor.NEUTRAL & 0xFF000000);
    }

    @Test
    public void saturatedColourKeepsHueAndSaturationButIsDarkened() {
        // Pure red: HSL lightness 0.5 -> 0.5 * 0.70 = 0.35, inside the [0.15, 0.65] band.
        int glow = GlowColor.glowFor(0xFF0000);
        float[] hsl = hslOf(glow);
        assertEquals(0f, hsl[0], 1f);        // hue preserved (red)
        assertEquals(1f, hsl[1], EPS);       // saturation preserved
        assertEquals(0.35f, hsl[2], EPS);    // lightness multiplied by 0.70
    }

    @Test
    public void lightnessIsClampedToTheDarkBand() {
        // Near-white saturated green: 0.95 * 0.70 = 0.665 -> clamped down to the 0.65 ceiling.
        int bright = GlowColor.fromHsl(new float[]{120f, 1f, 0.95f});
        assertEquals(GlowColor.LIGHT_MAX, hslOf(GlowColor.glowFor(bright))[2], EPS);

        // Very dark saturated blue: 0.08 * 0.70 = 0.056 -> clamped up to the 0.15 floor.
        int dark = GlowColor.fromHsl(new float[]{240f, 1f, 0.08f});
        assertEquals(GlowColor.LIGHT_MIN, hslOf(GlowColor.glowFor(dark))[2], EPS);
    }

    @Test
    public void greyscaleIconsFallBackToNeutral() {
        assertEquals(GlowColor.NEUTRAL, GlowColor.glowFor(0x808080)); // mid grey
        assertEquals(GlowColor.NEUTRAL, GlowColor.glowFor(0xFFFFFF)); // white
        assertEquals(GlowColor.NEUTRAL, GlowColor.glowFor(0x000000)); // black
    }

    @Test
    public void neutralThresholdIsSaturationBelowPoint15() {
        // Just below the threshold -> neutral; just above -> tinted (not neutral).
        int belowSat = GlowColor.fromHsl(new float[]{200f, 0.10f, 0.5f});
        int aboveSat = GlowColor.fromHsl(new float[]{200f, 0.20f, 0.5f});

        assertTrue(GlowColor.isNeutral(belowSat));
        assertFalse(GlowColor.isNeutral(aboveSat));

        assertEquals(GlowColor.NEUTRAL, GlowColor.glowFor(belowSat));
        float[] tinted = hslOf(GlowColor.glowFor(aboveSat));
        assertEquals(0.35f, tinted[2], EPS);    // darkened, not the neutral grey
        assertTrue(tinted[1] >= GlowColor.SAT_MIN);
    }

    @Test
    public void hslRoundTripIsStable() {
        int[] samples = {0xFF0000, 0x00FF00, 0x0000FF, 0x123456, 0xC0FFEE, 0x3A3A3A};
        for (int rgb : samples) {
            int back = GlowColor.fromHsl(hslOf(rgb));
            String tag = "round-trip of " + Integer.toHexString(rgb);
            assertEquals(tag, (rgb >> 16) & 0xFF, (back >> 16) & 0xFF, 1);
            assertEquals(tag, (rgb >> 8) & 0xFF, (back >> 8) & 0xFF, 1);
            assertEquals(tag, rgb & 0xFF, back & 0xFF, 1);
        }
    }
}
