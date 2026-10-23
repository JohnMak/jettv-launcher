package com.alexmak.atvlauncher;

/** Immutable lamp brightness level in {@link LampState#MIN_LEVEL}..{@link LampState#MAX_LEVEL}. */
public final class LampLevel {
    private final int value;

    private LampLevel(int value) {
        this.value = value;
    }

    public static FetchResult<LampLevel> of(int value) {
        if (value < LampState.MIN_LEVEL || value > LampState.MAX_LEVEL) {
            return FetchResult.err("lamp level must be " + LampState.MIN_LEVEL + ".." + LampState.MAX_LEVEL
                    + ": " + value);
        }
        return FetchResult.ok(new LampLevel(value));
    }

    public int value() { return value; }

    /** New level moved by delta, clamped to the valid range. */
    public LampLevel step(int delta) {
        long moved = (long) value + delta;
        long clamped = Math.max(LampState.MIN_LEVEL, Math.min(LampState.MAX_LEVEL, moved));
        return new LampLevel((int) clamped);
    }

    @Override public boolean equals(Object o) {
        return o instanceof LampLevel && ((LampLevel) o).value == value;
    }

    @Override public int hashCode() { return value; }

    @Override public String toString() { return "LampLevel(" + value + ")"; }
}
