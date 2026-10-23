package com.alexmak.atvlauncher;

/**
 * Inverse vignette of the page backdrop: a black overlay that is strongest in the centre and thinner towards the
 * edges, so the picture is darkest in the middle (content sits there) and most visible at the borders. The stops are
 * sampled from a smoothstep so the gradient has no visible kink; the bitmaps themselves are not dimmed.
 */
final class BackdropVignette {
    /** Share of the picture's brightness that is kept at the centre and at the screen edge/corners. */
    static final float CENTRE_BRIGHTNESS = 0.25f;
    static final float EDGE_BRIGHTNESS = 0.60f;
    private static final int STOPS = 8;

    private BackdropVignette() {
    }

    /** Gradient stop positions: 0 = centre ... 1 = ellipse through the screen corners. */
    static float[] positions() {
        float[] pos = new float[STOPS];
        for (int i = 0; i < STOPS; i++) pos[i] = i / (float) (STOPS - 1);
        return pos;
    }

    /** Alpha (0..255) of the black overlay at each stop. */
    static int[] alphas() {
        float[] pos = positions();
        int[] alpha = new int[STOPS];
        for (int i = 0; i < STOPS; i++) {
            float t = pos[i] * pos[i] * (3f - 2f * pos[i]);
            float brightness = CENTRE_BRIGHTNESS + (EDGE_BRIGHTNESS - CENTRE_BRIGHTNESS) * t;
            alpha[i] = Math.round(255 * (1f - brightness));
        }
        return alpha;
    }

    /** Colours (ARGB, black with the stop's alpha) for a gradient shader. */
    static int[] colors() {
        int[] alpha = alphas();
        int[] colors = new int[alpha.length];
        for (int i = 0; i < alpha.length; i++) colors[i] = alpha[i] << 24;
        return colors;
    }
}
