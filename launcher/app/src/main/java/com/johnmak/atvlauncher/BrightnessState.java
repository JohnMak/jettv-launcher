package com.johnmak.atvlauncher;

/** Immutable state of the brightness control: loading, ready at a scale position 0..15, or the failed shell channel kind. */
final class BrightnessState {
    enum Kind { LOADING, READY, ERROR }

    final Kind kind;
    /** Brightness scale position 0..15 for READY (0 = all dashes empty), meaningless otherwise. */
    final int position;
    /** What failed for ERROR, null otherwise. */
    final ShellError error;

    private BrightnessState(Kind kind, int position, ShellError error) {
        this.kind = kind;
        this.position = position;
        this.error = error;
    }

    static BrightnessState loading() {
        return new BrightnessState(Kind.LOADING, 0, null);
    }

    static BrightnessState ready(int position) {
        if (position < BrightnessScale.MIN_POSITION || position > BrightnessScale.MAX_POSITION) {
            throw new IllegalArgumentException("position must be 0..15: " + position);
        }
        return new BrightnessState(Kind.READY, position, null);
    }

    static BrightnessState error(ShellError error) {
        if (error == null) throw new IllegalArgumentException("error is null");
        return new BrightnessState(Kind.ERROR, 0, error);
    }
}
