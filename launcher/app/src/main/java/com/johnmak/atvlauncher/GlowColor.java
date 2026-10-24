package com.johnmak.atvlauncher;

/**
 * Pure colour maths for the focused-tile glow: turns an icon's dominant colour into a bright glow of the icon's
 * colour.
 *
 * <p>The glow is the dominant colour rescaled in HSL lightness so it reads as a clearly bright halo. Low-saturation
 * (greyscale) icons have no colour to tint with, so they fall back to a fixed neutral grey.
 *
 * <p>All maths here is plain Java (no {@code android.graphics}), so it is unit-tested on the JVM without a device.
 */
final class GlowColor {

    /** Neutral dark-grey glow for greyscale icons and as the until-resolved fallback. Opaque. */
    static final int NEUTRAL = 0xFF3A3A3A;

    /** Below this HSL saturation an icon is treated as greyscale and gets {@link #NEUTRAL}. */
    static final float SAT_MIN = 0.15f;

    // Multiply HSL lightness, then clamp into a bright band so very light/very dark icons both land on a clearly
    // visible, bright-but-tinted halo.
    static final float LIGHT_FACTOR = 0.70f;
    static final float LIGHT_MIN = 0.15f;
    static final float LIGHT_MAX = 0.65f;

    private GlowColor() {
    }

    /**
     * Glow colour for an icon's dominant RGB (alpha ignored). Greyscale dominant (saturation {@code < SAT_MIN}) ->
     * {@link #NEUTRAL}; otherwise the same hue/saturation with lightness {@code clamp(l * LIGHT_FACTOR, MIN, MAX)}
     * pushed into the bright band. The result is always opaque; the caller applies its own per-ring alpha.
     */
    static int glowFor(int rgb) {
        float[] hsl = new float[3];
        toHsl(rgb, hsl);
        if (hsl[1] < SAT_MIN) return NEUTRAL;
        hsl[2] = clamp(hsl[2] * LIGHT_FACTOR, LIGHT_MIN, LIGHT_MAX);
        return fromHsl(hsl);
    }

    /** True when a dominant colour is too desaturated to tint with (greyscale icon). */
    static boolean isNeutral(int rgb) {
        float[] hsl = new float[3];
        toHsl(rgb, hsl);
        return hsl[1] < SAT_MIN;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** RGB (0xRRGGBB, alpha ignored) -> HSL {h in [0,360), s in [0,1], l in [0,1]}. */
    static void toHsl(int rgb, float[] out) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float l = (max + min) / 2f;
        float h;
        float s;
        if (delta == 0f) {
            h = 0f;
            s = 0f;
        } else {
            s = l > 0.5f ? delta / (2f - max - min) : delta / (max + min);
            if (max == r) {
                h = (g - b) / delta + (g < b ? 6f : 0f);
            } else if (max == g) {
                h = (b - r) / delta + 2f;
            } else {
                h = (r - g) / delta + 4f;
            }
            h *= 60f;
        }
        out[0] = h;
        out[1] = s;
        out[2] = l;
    }

    /** HSL {h in [0,360), s in [0,1], l in [0,1]} -> opaque 0xFFRRGGBB. */
    static int fromHsl(float[] hsl) {
        float h = hsl[0] / 360f;
        float s = hsl[1];
        float l = hsl[2];
        float r;
        float g;
        float b;
        if (s == 0f) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }
        return 0xFF000000 | (round(r) << 16) | (round(g) << 8) | round(b);
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 1f / 2f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }

    private static int round(float channel) {
        int v = Math.round(channel * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
