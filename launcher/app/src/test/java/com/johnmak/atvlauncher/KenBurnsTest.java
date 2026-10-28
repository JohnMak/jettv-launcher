package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Offline tests for the pure Ken-Burns zoom math (plan B7): the FIT_CENTER base transform, the relative zoom
 * interpolation, and the pivot-fixed composition. No Android types are involved. */
public class KenBurnsTest {
    private static final float EPS = 1e-4f;

    @Test public void fitScaleIsTheLargestUniformScaleThatFitsInside() {
        assertEquals("4K into 1080p halves", 0.5f, KenBurns.fitScale(3840, 2160, 1920, 1080), EPS);
        assertEquals("equal size = 1.0", 1.0f, KenBurns.fitScale(1920, 1080, 1920, 1080), EPS);
        // A square source into a 16:9 view is limited by the SHORTER view edge (height).
        assertEquals("square source fits by height", 1080f / 2000f, KenBurns.fitScale(2000, 2000, 1920, 1080), EPS);
    }

    @Test public void fitScaleIsZeroForAnyNonPositiveDimension() {
        assertEquals(0f, KenBurns.fitScale(0, 100, 1920, 1080), EPS);
        assertEquals(0f, KenBurns.fitScale(100, 0, 1920, 1080), EPS);
        assertEquals(0f, KenBurns.fitScale(100, 100, 0, 1080), EPS);
        assertEquals(0f, KenBurns.fitScale(100, 100, 1920, 0), EPS);
    }

    @Test public void zoomStartsAtOneAndEndsAtAboutOnePointZeroEight() {
        assertEquals("start = 1.0 (exact fit)", 1.0f, KenBurns.ZOOM_START, EPS);
        assertEquals("end ≈ 1.08", 1.08f, KenBurns.ZOOM_END, EPS);
        assertEquals("fraction 0 -> start", KenBurns.ZOOM_START, KenBurns.zoomAt(0f), EPS);
        assertEquals("fraction 1 -> end", KenBurns.ZOOM_END, KenBurns.zoomAt(1f), EPS);
        assertEquals("linear midpoint", 1.04f, KenBurns.zoomAt(0.5f), EPS);
    }

    @Test public void zoomDurationIsTheDwell() {
        assertEquals("the zoom spans the on-screen dwell", ArtPlayback.DISPLAY_MS, KenBurns.DURATION_MS);
    }

    @Test public void zoomIsClampedOutsideTheUnitInterval() {
        assertEquals(KenBurns.ZOOM_START, KenBurns.zoomAt(-0.5f), EPS);
        assertEquals(KenBurns.ZOOM_END, KenBurns.zoomAt(2f), EPS);
    }

    @Test public void baseFitPreservesTheFitFramingAndIsNotAnIdentityScale() {
        // Regression guard: an identity+scale matrix would leave scale 1.0 and translate 0 — losing the fit.
        float[] base = KenBurns.baseFit(3840, 2160, 1920, 1080);
        assertEquals("base scale fits the 4K source to the panel (NOT identity 1.0)", 0.5f, base[0], EPS);
        assertTrue("the base scale is not the identity", Math.abs(base[0] - 1.0f) > EPS);
        // Matching aspect ratios => no letterbox translation.
        assertEquals(0f, base[1], EPS);
        assertEquals(0f, base[2], EPS);
    }

    @Test public void baseFitCentresTheLetterboxForAMismatchedAspect() {
        // A 2000x2000 square into 1920x1080: scaled to 1080x1080, centred => 420px bars left/right, 0 top/bottom.
        float[] base = KenBurns.baseFit(2000, 2000, 1920, 1080);
        float scale = 1080f / 2000f;
        assertEquals(scale, base[0], EPS);
        assertEquals("horizontally centred (letterbox)", (1920f - 2000f * scale) / 2f, base[1], EPS);
        assertEquals("vertically flush", 0f, base[2], EPS);
    }

    @Test public void transformAtZoomOneEqualsTheBaseFit() {
        float[] base = KenBurns.baseFit(3840, 2160, 1920, 1080);
        float[] at1 = KenBurns.transformAt(3840, 2160, 1920, 1080, 1.0f, 960f, 540f);
        assertEquals(base[0], at1[0], EPS);
        assertEquals(base[1], at1[1], EPS);
        assertEquals(base[2], at1[2], EPS);
    }

    @Test public void transformAtScalesRelativeToTheFitNotFromIdentity() {
        float fit = KenBurns.fitScale(3840, 2160, 1920, 1080);
        float[] at = KenBurns.transformAt(3840, 2160, 1920, 1080, KenBurns.ZOOM_END, 960f, 540f);
        assertEquals("effective scale = zoom × fit, never just the zoom", KenBurns.ZOOM_END * fit, at[0], EPS);
    }

    @Test public void theCentrePivotStaysFixedAsTheImageZooms() {
        // The fit-centred image's centre sits at the view centre; a centre-pivot zoom must keep that point fixed.
        int srcW = 3840, srcH = 2160, viewW = 1920, viewH = 1080;
        float pivotX = viewW / 2f, pivotY = viewH / 2f;
        for (float zoom : new float[]{1.0f, 1.04f, KenBurns.ZOOM_END}) {
            float[] t = KenBurns.transformAt(srcW, srcH, viewW, viewH, zoom, pivotX, pivotY);
            float mappedX = t[0] * (srcW / 2f) + t[1]; // source centre mapped to view space
            float mappedY = t[0] * (srcH / 2f) + t[2];
            assertEquals("pivot X fixed at zoom " + zoom, pivotX, mappedX, 1e-2f);
            assertEquals("pivot Y fixed at zoom " + zoom, pivotY, mappedY, 1e-2f);
        }
    }

    @Test public void transformIsEmptyForAnUnsizedLayer() {
        float[] t = KenBurns.transformAt(0, 0, 1920, 1080, 1.08f, 960f, 540f);
        assertEquals(0f, t[0], EPS);
        assertEquals(0f, t[1], EPS);
        assertEquals(0f, t[2], EPS);
    }
}
