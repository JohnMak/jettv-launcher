package com.johnmak.atvlauncher;

/**
 * Pure geometry and cache key of a tile shadow: a blurred rounded rectangle of the tile's size,
 * pre-rendered once into a bitmap padded by the blur radius on every side and drawn shifted down.
 * All values are pixels.
 */
final class ShadowGeometry {
    static final float BLUR_DP = 20;
    static final float OFFSET_DP = 5;
    /** Black at this opacity (0..255). */
    static final int ALPHA = 107;

    final int width;
    final int height;
    final int radius;
    final int blur;
    final int offsetY;

    ShadowGeometry(int width, int height, int radius, int blur, int offsetY) {
        if (width <= 0 || height <= 0 || radius < 0 || blur <= 0 || offsetY < 0) {
            throw new IllegalArgumentException("bad shadow geometry " + width + "x" + height
                    + " r" + radius + " blur" + blur + " dy" + offsetY);
        }
        this.width = width;
        this.height = height;
        this.radius = radius;
        this.blur = blur;
        this.offsetY = offsetY;
    }

    static ShadowGeometry of(int width, int height, float radiusPx, float density) {
        return new ShadowGeometry(width, height, Math.round(radiusPx),
                Math.round(BLUR_DP * density), Math.round(OFFSET_DP * density));
    }

    int bitmapWidth() {
        return width + 2 * blur;
    }

    int bitmapHeight() {
        return height + 2 * blur;
    }

    /** Bitmap left edge relative to the tile's left edge. */
    int drawLeft() {
        return -blur;
    }

    /** Bitmap top edge relative to the tile's top edge (shifted down by the offset). */
    int drawTop() {
        return -blur + offsetY;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ShadowGeometry)) return false;
        ShadowGeometry g = (ShadowGeometry) o;
        return width == g.width && height == g.height && radius == g.radius
                && blur == g.blur && offsetY == g.offsetY;
    }

    @Override
    public int hashCode() {
        int h = width;
        h = 31 * h + height;
        h = 31 * h + radius;
        h = 31 * h + blur;
        h = 31 * h + offsetY;
        return h;
    }
}
