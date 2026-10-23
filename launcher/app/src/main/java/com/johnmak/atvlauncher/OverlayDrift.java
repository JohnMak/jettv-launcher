package com.johnmak.atvlauncher;

import java.util.Random;

/**
 * Pure burn-in protection logic for the centred clock overlay: a small offset from the screen
 * centre, as fractions of the screen width (x) and height (y). Every {@link #next()} picks a new
 * target within +-MAX_OFFSET of the centre at least MIN_STEP away from the current one; the view
 * moves there slowly.
 */
public final class OverlayDrift {
    public static final long INTERVAL_MS = 120_000L;
    public static final float MAX_OFFSET = 0.04f;
    public static final float MIN_STEP = 0.025f;
    private static final int MAX_ATTEMPTS = 1000;

    private final Random random;
    private float curX;
    private float curY;

    public OverlayDrift(long seed) {
        random = new Random(seed);
        curX = randomOffset();
        curY = randomOffset();
    }

    private float randomOffset() {
        return (random.nextFloat() * 2f - 1f) * MAX_OFFSET;
    }

    /** Horizontal offset from the centre, fraction of width. */
    public float x() {
        return curX;
    }

    /** Vertical offset from the centre, fraction of height. */
    public float y() {
        return curY;
    }

    /** Choose the next target (distance from the current one >= MIN_STEP). */
    public void next() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            float nx = randomOffset();
            float ny = randomOffset();
            if (Math.hypot(nx - curX, ny - curY) >= MIN_STEP) {
                curX = nx;
                curY = ny;
                return;
            }
        }
        throw new IllegalStateException("no drift position found");
    }
}
