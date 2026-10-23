package com.johnmak.atvlauncher;

/**
 * Frame-rate independent exponential approach to a moving target: the one motion curve of the glass and of the row
 * scrolling. A target may change at any time; the value simply keeps heading for the latest one (no restart, no jerk).
 * The glass and the rows share {@link #TAU_MS}, so the glass stays on its tile while the row is still moving.
 */
final class Approach {
    /** Time constant: about 95% of a distance is covered after 3 * TAU_MS. */
    static final double TAU_MS = 40;

    private Approach() {
    }

    /** One step of {@code dtMs}; snaps to the target once closer than {@code eps}. */
    static double step(double current, double target, double dtMs, double eps) {
        double next = current + (target - current) * (1 - Math.exp(-dtMs / TAU_MS));
        return Math.abs(target - next) < eps ? target : next;
    }
}
