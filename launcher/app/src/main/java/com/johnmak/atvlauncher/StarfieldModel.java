package com.johnmak.atvlauncher;

import java.util.Random;

/**
 * Pure starfield simulation (no Android imports). Stars live in a unit cube: x,y in [-1,1],
 * z in (0,1] where z=1 is far and z -> 0 is the camera. Perspective: screen offset = x / z.
 * A star is respawned at the far plane when it passes the camera or leaves the view.
 *
 * <p>Spawn positions come from a few slowly moving cluster centres (gaussian spread) mixed with a
 * uniform background, so the flight passes through denser and sparser regions. Each star has a
 * fixed size class (power-law: mostly tiny) and colour class (mostly white), re-rolled on respawn.
 */
public final class StarfieldModel {
    public static final int DEFAULT_STAR_COUNT = 2000;
    /** Nearest allowed depth; stars below it have passed the camera. */
    public static final float MIN_Z = 0.05f;
    /** Brightness cap (OLED friendly). */
    public static final float MAX_BRIGHTNESS = 0.85f;
    /** Brightness of the farthest stars. */
    public static final float MIN_BRIGHTNESS = 0.15f;
    /** Depth units per second: a star travelling from z=1 to z=MIN_Z takes about 30 s. */
    public static final float DEFAULT_SPEED = (1f - MIN_Z) / 30f;

    public static final int SIZE_CLASSES = 4;
    /** Colour classes: 0 white, 1 bluish, 2 warm yellowish, 3 faint orange-red. */
    public static final int COLOR_CLASSES = 4;
    /** Number of discrete brightness levels used for batching. */
    public static final int BRIGHTNESS_LEVELS = 3;

    public static final int CLUSTER_COUNT = 4;
    /** Share of stars spawned around cluster centres; the rest is uniform background. */
    public static final float CLUSTER_SHARE = 0.65f;
    public static final float CLUSTER_MIN_SIGMA = 0.10f;
    public static final float CLUSTER_MAX_SIGMA = 0.18f;
    /** Cluster centres stay within +-CENTRE_LIMIT and move at most CENTRE_MAX_SPEED per second. */
    public static final float CENTRE_LIMIT = 0.75f;
    public static final float CENTRE_MAX_SPEED = 0.03f;

    /** Cumulative probabilities of size classes 0..3 (power-law-like: mostly 1px crumbs). */
    private static final float[] SIZE_CUMULATIVE = {0.78f, 0.93f, 0.985f, 1f};
    /** Cumulative probabilities of colour classes 0..3. */
    private static final float[] COLOR_CUMULATIVE = {0.68f, 0.84f, 0.96f, 1f};
    /** Projected |offset| above this means the star is outside the screen and is respawned. */
    private static final float VIEW_LIMIT = 1.05f;

    private final Random random;
    private final float speed;
    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final byte[] sizeClass;
    private final byte[] colorClass;
    private final float[] centreX = new float[CLUSTER_COUNT];
    private final float[] centreY = new float[CLUSTER_COUNT];
    private final float[] centreVx = new float[CLUSTER_COUNT];
    private final float[] centreVy = new float[CLUSTER_COUNT];
    private final float[] centreSigma = new float[CLUSTER_COUNT];

    public StarfieldModel(int count, long seed, float speed) {
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        if (speed <= 0f) throw new IllegalArgumentException("speed must be > 0");
        this.random = new Random(seed);
        this.speed = speed;
        x = new float[count];
        y = new float[count];
        z = new float[count];
        sizeClass = new byte[count];
        colorClass = new byte[count];
        for (int c = 0; c < CLUSTER_COUNT; c++) {
            centreX[c] = (random.nextFloat() * 2f - 1f) * CENTRE_LIMIT;
            centreY[c] = (random.nextFloat() * 2f - 1f) * CENTRE_LIMIT;
            centreVx[c] = (random.nextFloat() * 2f - 1f) * CENTRE_MAX_SPEED;
            centreVy[c] = (random.nextFloat() * 2f - 1f) * CENTRE_MAX_SPEED;
            centreSigma[c] = CLUSTER_MIN_SIGMA + random.nextFloat() * (CLUSTER_MAX_SIGMA - CLUSTER_MIN_SIGMA);
        }
        for (int i = 0; i < count; i++) {
            spawn(i, MIN_Z + random.nextFloat() * (1f - MIN_Z));
        }
    }

    public StarfieldModel(long seed) {
        this(DEFAULT_STAR_COUNT, seed, DEFAULT_SPEED);
    }

    private static int pick(float[] cumulative, float u) {
        for (int k = 0; k < cumulative.length - 1; k++) {
            if (u < cumulative[k]) return k;
        }
        return cumulative.length - 1;
    }

    private static float clampUnit(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private void spawn(int i, float depth) {
        if (random.nextFloat() < CLUSTER_SHARE) {
            int c = random.nextInt(CLUSTER_COUNT);
            x[i] = clampUnit(centreX[c] + (float) random.nextGaussian() * centreSigma[c]);
            y[i] = clampUnit(centreY[c] + (float) random.nextGaussian() * centreSigma[c]);
        } else {
            x[i] = random.nextFloat() * 2f - 1f;
            y[i] = random.nextFloat() * 2f - 1f;
        }
        z[i] = depth;
        sizeClass[i] = (byte) pick(SIZE_CUMULATIVE, random.nextFloat());
        colorClass[i] = (byte) pick(COLOR_CUMULATIVE, random.nextFloat());
    }

    public int count() {
        return x.length;
    }

    public float depth(int i) {
        return z[i];
    }

    /** Size class 0 (tiny crumb, most common) .. SIZE_CLASSES-1 (rare, biggest). */
    public int sizeClass(int i) {
        return sizeClass[i];
    }

    /** Colour class 0 (white, most common) .. COLOR_CLASSES-1. */
    public int colorClass(int i) {
        return colorClass[i];
    }

    /** Discrete brightness level 0 (far) .. BRIGHTNESS_LEVELS-1 (near). */
    public int brightnessLevel(int i) {
        int level = (int) ((1f - z[i]) * BRIGHTNESS_LEVELS);
        return Math.max(0, Math.min(BRIGHTNESS_LEVELS - 1, level));
    }

    /** Advance the simulation by dtSeconds (>= 0). */
    public void step(float dtSeconds) {
        if (dtSeconds < 0f) throw new IllegalArgumentException("dt must be >= 0");
        for (int c = 0; c < CLUSTER_COUNT; c++) {
            centreX[c] += centreVx[c] * dtSeconds;
            centreY[c] += centreVy[c] * dtSeconds;
            if (Math.abs(centreX[c]) > CENTRE_LIMIT) {
                centreX[c] = Math.copySign(CENTRE_LIMIT, centreX[c]);
                centreVx[c] = -centreVx[c];
            }
            if (Math.abs(centreY[c]) > CENTRE_LIMIT) {
                centreY[c] = Math.copySign(CENTRE_LIMIT, centreY[c]);
                centreVy[c] = -centreVy[c];
            }
        }
        for (int i = 0; i < x.length; i++) {
            z[i] -= speed * dtSeconds;
            boolean passed = z[i] <= MIN_Z;
            boolean outside = Math.abs(x[i] / z[i]) > VIEW_LIMIT || Math.abs(y[i] / z[i]) > VIEW_LIMIT;
            if (passed || outside) {
                spawn(i, 1f);
            }
        }
    }

    /** Screen x in pixels for a screen of the given width. */
    public float screenX(int i, int width) {
        return width * 0.5f * (1f + x[i] / z[i]);
    }

    /** Screen y in pixels for a screen of the given height. */
    public float screenY(int i, int height) {
        return height * 0.5f * (1f + y[i] / z[i]);
    }

    /** Brightness 0..1 (near = brighter), never above MAX_BRIGHTNESS. */
    public float brightness(int i) {
        float near = 1f - z[i];
        float b = MIN_BRIGHTNESS + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * near;
        return Math.min(MAX_BRIGHTNESS, b);
    }

    /** Brightness of a discrete level (centre of its depth band), within [MIN, MAX]_BRIGHTNESS. */
    public static float levelBrightness(int level) {
        float near = (level + 0.5f) / BRIGHTNESS_LEVELS;
        return MIN_BRIGHTNESS + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * near;
    }
}
