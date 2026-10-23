package com.alexmak.atvlauncher;

/**
 * Pure maths of a soft star sprite (no Android imports): a gaussian dot whose full width at half maximum is the
 * star's footprint. Drawn with bilinear filtering at a float position the intensity spreads over the neighbouring
 * pixels, so sub-pixel motion is smooth. Stars smaller than {@link #MIN_FOOTPRINT_PX} are rendered at that
 * footprint and dimmed by the area ratio, so they keep looking tiny without flickering.
 */
final class StarSprite {
    /** No star is rendered with a smaller footprint (FWHM) than this, in pixels. */
    static final float MIN_FOOTPRINT_PX = 1.5f;
    /** The sprite extends this many sigmas from the centre (exp(-4.5) = 1 % at the border). */
    private static final float EXTENT_SIGMAS = 3f;
    private static final float FWHM_PER_SIGMA = 2.3548f;

    private StarSprite() {}

    /** Rendered footprint (FWHM) of a star of the given nominal size. */
    static float footprintPx(float sizePx) {
        return Math.max(sizePx, MIN_FOOTPRINT_PX);
    }

    /** Alpha multiplier (0..1) that keeps the energy of a star smaller than the minimum footprint. */
    static float alphaScale(float sizePx) {
        float r = sizePx / footprintPx(sizePx);
        return r * r;
    }

    static float sigmaPx(float footprintPx) {
        return footprintPx / FWHM_PER_SIGMA;
    }

    /** Odd side length so that the peak sits on a pixel centre. */
    static int side(float sigmaPx) {
        return 2 * (int) Math.ceil(EXTENT_SIGMAS * sigmaPx) + 1;
    }

    /** ALPHA_8 pixels, row-major, side*side, peak 255 in the centre. */
    static byte[] alpha(float sigmaPx) {
        int side = side(sigmaPx);
        int c = side / 2;
        byte[] out = new byte[side * side];
        float k = 1f / (2f * sigmaPx * sigmaPx);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                float d2 = (x - c) * (x - c) + (y - c) * (y - c);
                out[y * side + x] = (byte) Math.round(255f * (float) Math.exp(-d2 * k));
            }
        }
        return out;
    }
}
