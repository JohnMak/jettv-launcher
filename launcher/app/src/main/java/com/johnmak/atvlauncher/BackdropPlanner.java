package com.johnmak.atvlauncher;

/**
 * Decides when the poster backdrop changes. The wanted poster (null = no backdrop, colour tint only) is
 * debounced so fast D-pad scrolling does not thrash; a poster is committed only once it is old enough.
 */
final class BackdropPlanner {
    static final long DEBOUNCE_MS = 150;

    private String wanted;
    private String applied;
    private long changedAt;

    /** Sets the poster that should be shown now; restarts the debounce only when it changed. */
    void want(String posterUri, long nowMs) {
        if (java.util.Objects.equals(wanted, posterUri)) return;
        wanted = posterUri;
        changedAt = nowMs;
    }

    String wanted() {
        return wanted;
    }

    /** True when the wanted poster differs from the shown one and has been stable for the debounce time. */
    boolean ready(long nowMs) {
        return !java.util.Objects.equals(wanted, applied) && nowMs - changedAt >= DEBOUNCE_MS;
    }

    /** Milliseconds until {@link #ready} can become true (0 when already due). */
    long delayMs(long nowMs) {
        return Math.max(0, DEBOUNCE_MS - (nowMs - changedAt));
    }

    /** The shown backdrop was dropped (memory trim): the wanted key counts as not shown and is rendered again. */
    void forgetApplied() {
        applied = null;
    }

    /** Marks the wanted poster as shown. */
    void commit() {
        applied = wanted;
    }
}
