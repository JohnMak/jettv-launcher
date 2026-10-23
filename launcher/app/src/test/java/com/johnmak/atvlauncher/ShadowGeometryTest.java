package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ShadowGeometryTest {
    @Test
    public void bitmapIsPaddedByBlurAndDrawnShiftedDown() {
        ShadowGeometry g = ShadowGeometry.of(300, 170, 24f, 2f);
        assertEquals(40, g.blur);
        assertEquals(10, g.offsetY);
        assertEquals(380, g.bitmapWidth());
        assertEquals(250, g.bitmapHeight());
        assertEquals(-40, g.drawLeft());
        assertEquals(-30, g.drawTop());
        // the shadow reaches blur + offset below the tile and blur - offset above it
        assertEquals(50, g.drawTop() + g.bitmapHeight() - g.height);
    }

    @Test
    public void cacheKeyIsTheWholeGeometry() {
        ShadowGeometry a = ShadowGeometry.of(300, 170, 24f, 2f);
        assertEquals(a, ShadowGeometry.of(300, 170, 24f, 2f));
        assertEquals(a.hashCode(), ShadowGeometry.of(300, 170, 24f, 2f).hashCode());
        assertNotEquals(a, ShadowGeometry.of(301, 170, 24f, 2f));
        assertNotEquals(a, ShadowGeometry.of(300, 170, 20f, 2f));
        assertNotEquals(a, ShadowGeometry.of(300, 170, 24f, 1f));
    }

    @Test
    public void emptyTileHasNoShadow() {
        assertThrows(IllegalArgumentException.class, () -> ShadowGeometry.of(0, 170, 24f, 2f));
    }
}
