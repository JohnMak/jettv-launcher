package com.johnmak.atvlauncher;

/** Immutable lamp brightness level in {@link #MIN_LEVEL}..{@link #MAX_LEVEL}. */
final class LampLevel {
    static final int MIN_LEVEL = 1;
    static final int MAX_LEVEL = 10;

    private final int value;

    private LampLevel(int value) {
        this.value = value;
    }

    static FetchResult<LampLevel> of(int value) {
        if (value < LampLevel.MIN_LEVEL || value > LampLevel.MAX_LEVEL) {
            return FetchResult.err("lamp level must be " + LampLevel.MIN_LEVEL + ".." + LampLevel.MAX_LEVEL
                    + ": " + value);
        }
        return FetchResult.ok(new LampLevel(value));
    }

    int value() { return value; }

    @Override public boolean equals(Object o) {
        return o instanceof LampLevel && ((LampLevel) o).value == value;
    }

    @Override public int hashCode() { return value; }

    @Override public String toString() { return "LampLevel(" + value + ")"; }
}
