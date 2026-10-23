package com.alexmak.atvlauncher;

/**
 * The unified 16-position brightness scale 0..15 (= number of filled dashes). Positions 0..2 dim the picture at the
 * minimum lamp level, 3..12 walk the lamp 1..10 at the neutral picture, 13..15 boost the picture at the maximum lamp
 * level. Brightness and contrast always carry the same picture value.
 */
final class BrightnessScale {
    static final int MIN_POSITION = 0;
    static final int MAX_POSITION = 15;
    /** Number of positions left of the lamp zone (picture below neutral) and right of it (above neutral). */
    static final int ZONE_SIZE = 3;
    static final int NEUTRAL_PICTURE = 50;

    /** Picture value per position, index = position; the only place these numbers live. */
    private static final int[] PICTURE = {
            27, 35, 42,
            50, 50, 50, 50, 50, 50, 50, 50, 50, 50,
            57, 65, 72};

    private BrightnessScale() {}

    /** Immutable position in {@link #MIN_POSITION}..{@link #MAX_POSITION}. */
    static final class Position {
        private final int value;

        private Position(int value) {
            this.value = value;
        }

        static FetchResult<Position> of(int value) {
            if (value < MIN_POSITION || value > MAX_POSITION) {
                return FetchResult.err("brightness position must be " + MIN_POSITION + ".." + MAX_POSITION
                        + ": " + value);
            }
            return FetchResult.ok(new Position(value));
        }

        int value() { return value; }

        /** New position moved by delta, clamped to the valid range. */
        Position step(int delta) {
            long moved = (long) value + delta;
            long clamped = Math.max(MIN_POSITION, Math.min(MAX_POSITION, moved));
            return new Position((int) clamped);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Position && ((Position) o).value == value;
        }

        @Override public int hashCode() { return value; }

        @Override public String toString() { return "Position(" + value + ")"; }
    }

    static LampLevel lamp(Position p) {
        int level = Math.max(LampLevel.MIN_LEVEL, Math.min(LampLevel.MAX_LEVEL, p.value - (ZONE_SIZE - 1)));
        return LampLevel.of(level).value();
    }

    static int picture(Position p) {
        return PICTURE[p.value];
    }

    /**
     * Nearest position for values read from the device. Off-grid picture values inside the lamp zone map by lamp;
     * the next write normalizes the picture to the table.
     */
    static Position fromDevice(LampLevel lamp, int brightness, int contrast) {
        double picture = (brightness + contrast) / 2.0;
        int first;
        if (lamp.value() == LampLevel.MIN_LEVEL && picture < NEUTRAL_PICTURE) {
            first = MIN_POSITION;
        } else if (lamp.value() == LampLevel.MAX_LEVEL && picture > NEUTRAL_PICTURE) {
            first = MAX_POSITION - ZONE_SIZE + 1; // 13
        } else {
            return Position.of(lamp.value() + ZONE_SIZE - 1).value();
        }
        int best = first;
        for (int pos = first; pos < first + ZONE_SIZE; pos++) {
            if (Math.abs(PICTURE[pos] - picture) < Math.abs(PICTURE[best] - picture)) best = pos;
        }
        return Position.of(best).value();
    }
}
