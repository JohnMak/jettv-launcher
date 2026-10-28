package com.johnmak.atvlauncher;

/**
 * The pure math of the ART screensaver's slow Ken-Burns zoom (plan B7): a FIT_CENTER base transform plus a very slow
 * relative zoom from {@link #ZOOM_START} to {@link #ZOOM_END} over the dwell, applied around a pivot. No Android types
 * (no {@code Matrix}) so it is unit-tested offline; {@code ArtScreensaverView.ViewStage} feeds the resulting
 * {@code (scale, transX, transY)} into a real {@link android.graphics.Matrix} on each animation frame.
 *
 * <p>The zoom is RELATIVE to the fit, never an identity+scale (which would pin the image native-size at the origin and
 * lose the FIT_CENTER framing). The base transform fits the source inside the view preserving aspect ratio and centers
 * it (the letterbox); the zoom then multiplies that scale about the pivot, so a point at the pivot stays fixed while
 * the image grows. Because the zoom only ever scales UP (≥ 1.0), it never reveals a gap at the view edges.
 */
final class KenBurns {
    /** The zoom at the start of the dwell — exactly the FIT_CENTER framing. */
    static final float ZOOM_START = 1.0f;
    /** The zoom at the end of the dwell — a barely-perceptible 8% magnification. */
    static final float ZOOM_END = 1.08f;
    /** The zoom animation duration = the on-screen dwell ({@link ArtPlayback#DISPLAY_MS}); the animator is aligned with
     * the dwell timer at each start site (plan B7). */
    static final long DURATION_MS = ArtPlayback.DISPLAY_MS;

    private KenBurns() {}

    /** The FIT_CENTER scale: the largest uniform scale that fits {@code src} entirely inside {@code view}
     * ({@code min(viewW/srcW, viewH/srcH)}). {@code 0} for any non-positive dimension (nothing to render). */
    static float fitScale(int srcW, int srcH, int viewW, int viewH) {
        if (srcW <= 0 || srcH <= 0 || viewW <= 0 || viewH <= 0) return 0f;
        float sx = (float) viewW / srcW;
        float sy = (float) viewH / srcH;
        return Math.min(sx, sy);
    }

    /** The interpolated relative zoom at {@code fraction} in {@code [0,1]} (linear; clamped). {@code 0→}{@link
     * #ZOOM_START}, {@code 1→}{@link #ZOOM_END}. */
    static float zoomAt(float fraction) {
        float f = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        return ZOOM_START + (ZOOM_END - ZOOM_START) * f;
    }

    /**
     * The effective {@code [scale, transX, transY]} of the base FIT_CENTER matrix at {@code zoom = 1.0}: the fit scale
     * and the centering translation (the letterbox offset). Deliberately NOT the identity — unit tests assert this to
     * guard against the "identity+scale loses the fit" regression. Empty-size inputs yield {@code [0,0,0]}.
     */
    static float[] baseFit(int srcW, int srcH, int viewW, int viewH) {
        float scale = fitScale(srcW, srcH, viewW, viewH);
        if (scale <= 0f) return new float[]{0f, 0f, 0f};
        float transX = (viewW - srcW * scale) / 2f;
        float transY = (viewH - srcH * scale) / 2f;
        return new float[]{scale, transX, transY};
    }

    /**
     * The effective {@code [scale, transX, transY]} after applying a relative {@code zoom} about pivot
     * {@code (pivotX, pivotY)} (view coordinates) to the base FIT_CENTER transform — equivalent to
     * {@code Matrix.set(base); Matrix.postScale(zoom, zoom, pivotX, pivotY)}. {@code scale = zoom · fitScale};
     * {@code trans = zoom · baseTrans + pivot · (1 − zoom)}, which keeps the pivot point fixed as the image scales.
     */
    static float[] transformAt(int srcW, int srcH, int viewW, int viewH, float zoom, float pivotX, float pivotY) {
        float[] base = baseFit(srcW, srcH, viewW, viewH);
        if (base[0] <= 0f) return base;
        float scale = zoom * base[0];
        float transX = zoom * base[1] + pivotX * (1f - zoom);
        float transY = zoom * base[2] + pivotY * (1f - zoom);
        return new float[]{scale, transX, transY};
    }
}
