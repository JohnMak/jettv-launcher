package com.alexmak.atvlauncher;

/** Immutable state of the lamp brightness control: loading, ready at a level 1..10, or an error message. */
public final class LampState {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;

    public enum Kind { LOADING, READY, ERROR }

    public final Kind kind;
    /** Brightness level 1..10 for READY, 0 otherwise. */
    public final int level;
    /** Error text for ERROR, null otherwise. */
    public final String message;

    private LampState(Kind kind, int level, String message) {
        this.kind = kind;
        this.level = level;
        this.message = message;
    }

    public static LampState loading() {
        return new LampState(Kind.LOADING, 0, null);
    }

    public static LampState ready(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be 1..10: " + level);
        }
        return new LampState(Kind.READY, level, null);
    }

    public static LampState error(String message) {
        if (message == null) throw new IllegalArgumentException("message is null");
        return new LampState(Kind.ERROR, 0, message);
    }
}
