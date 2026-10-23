package com.alexmak.atvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Builds the small blurred, dimmed, 16:9 backdrop bitmap from a decoded poster: 160x90 opaque ARGB_8888 pixels (57.6 KB), shown
 * bilinear-stretched over the screen. The source is downscaled by progressive halving (never one huge jump) and
 * blurred with three box passes (~gaussian, sigma ~ 9% of the width).
 */
final class BackdropBlur {
    /** Pixel format of every backdrop bitmap; 565 bands visibly on the dark, smooth blurred gradients. */
    static final Bitmap.Config CONFIG = Bitmap.Config.ARGB_8888;
    static final int W = 160;
    static final int H = 90;
    /** Box radius of each of the three passes; (2r+1)^2 - 1 over 12, three times, gives sigma ~ 14 px of 160. */
    static final int RADIUS = 14;
    static final int PASSES = 3;
    /** The bitmaps are not dimmed; the dim is the {@link BackdropVignette} overlay. */
    static final float BRIGHTNESS = 1f;

    private BackdropBlur() {
    }

    /** Centre-crops {@code src} to 16:9, halves it down to 160x90 with bilinear filtering, then blurs and dims. */
    static Bitmap render(Bitmap src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int cropW = Math.min(sw, sh * 16 / 9);
        int cropH = Math.min(sh, sw * 9 / 16);
        Bitmap current = Bitmap.createBitmap(src, (sw - cropW) / 2, (sh - cropH) / 2, cropW, cropH);
        for (int[] size : halvingSteps(cropW, cropH, W, H)) {
            Bitmap next = Bitmap.createScaledBitmap(current, size[0], size[1], true);
            if (current != src) current.recycle();
            current = next;
        }
        Bitmap small = current.copy(Bitmap.Config.ARGB_8888, true);
        if (current != src) current.recycle();
        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        blurAndDim(px, W, H, RADIUS, PASSES, BRIGHTNESS);
        small.recycle();
        return Bitmap.createBitmap(px, W, H, CONFIG);
    }

    /**
     * Sizes of the successive downscale steps from w x h to exactly tw x th: each step at most halves a side, the
     * last one lands on the target. A source that is not larger than the target gives the single target step.
     */
    static java.util.List<int[]> halvingSteps(int w, int h, int tw, int th) {
        java.util.List<int[]> steps = new java.util.ArrayList<>();
        int cw = w;
        int ch = h;
        while (cw > tw || ch > th) {
            cw = Math.max(tw, (cw + 1) / 2);
            ch = Math.max(th, (ch + 1) / 2);
            steps.add(new int[]{cw, ch});
        }
        if (steps.isEmpty()) steps.add(new int[]{tw, th});
        return steps;
    }

    /** {@code passes} separable box blurs (horizontal + vertical each) with edge clamping, then brightness multiply; result is opaque. */
    static void blurAndDim(int[] px, int w, int h, int radius, int passes, float brightness) {
        int[] tmp = new int[px.length];
        for (int i = 0; i < passes; i++) {
            boxPass(px, tmp, w, h, radius, true);
            boxPass(tmp, px, w, h, radius, false);
        }
        for (int i = 0; i < px.length; i++) {
            int r = Math.round(((px[i] >> 16) & 0xFF) * brightness);
            int g = Math.round(((px[i] >> 8) & 0xFF) * brightness);
            int b = Math.round((px[i] & 0xFF) * brightness);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private static void boxPass(int[] in, int[] out, int w, int h, int radius, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int step = horizontal ? 1 : w;
        int lineStep = horizontal ? w : 1;
        int count = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            for (int i = 0; i < len; i++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = in[base + Math.max(0, Math.min(len - 1, i + k)) * step];
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                }
                out[base + i * step] = 0xFF000000 | (((r + count / 2) / count) << 16) | (((g + count / 2) / count) << 8) | ((b + count / 2) / count);
            }
        }
    }
}
