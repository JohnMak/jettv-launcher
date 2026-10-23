package com.alexmak.atvlauncher;

/**
 * Scroll position of one row heading for a retargetable target (see {@link Approach}). Pure; {@link RowScroller}
 * drives it from animation frames and turns {@link #pos()} into scrollBy calls.
 */
final class RowScrollModel {
    private static final double EPS_PX = 1;

    private double pos;
    private double target;

    double pos() {
        return pos;
    }

    double target() {
        return target;
    }

    /** Distance still to scroll. */
    double remaining() {
        return target - pos;
    }

    boolean settled() {
        return pos == target;
    }

    /** Moves the target by {@code dx}; the position keeps going without restarting. */
    void retargetBy(double dx) {
        target += dx;
    }

    void step(double dtMs) {
        pos = Approach.step(pos, target, dtMs, EPS_PX);
    }

    void reset() {
        pos = 0;
        target = 0;
    }
}
