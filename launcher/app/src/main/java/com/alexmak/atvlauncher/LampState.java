package com.alexmak.atvlauncher;

/** Immutable state of the brightness control: loading, ready at a scale position 0..15, or an error message. */
public final class LampState {
    public enum Kind { LOADING, READY, ERROR }

    public final Kind kind;
    /** Brightness scale position 0..15 for READY (0 = all dashes empty), meaningless otherwise. */
    public final int position;
    /** Error text for ERROR, null otherwise. */
    public final String message;

    private LampState(Kind kind, int position, String message) {
        this.kind = kind;
        this.position = position;
        this.message = message;
    }

    public static LampState loading() {
        return new LampState(Kind.LOADING, 0, null);
    }

    public static LampState ready(int position) {
        if (position < BrightnessScale.MIN_POSITION || position > BrightnessScale.MAX_POSITION) {
            throw new IllegalArgumentException("position must be 0..15: " + position);
        }
        return new LampState(Kind.READY, position, null);
    }

    public static LampState error(String message) {
        if (message == null) throw new IllegalArgumentException("message is null");
        return new LampState(Kind.ERROR, 0, message);
    }
}
