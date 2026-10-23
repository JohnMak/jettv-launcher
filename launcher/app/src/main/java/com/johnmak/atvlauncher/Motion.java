package com.johnmak.atvlauncher;

/**
 * Animation policy from {@link LauncherSettings#animationsOff}: focus and backdrop changes become instant,
 * scrolling stays animated. Screens build it in onResume, so a changed setting applies on return.
 */
final class Motion {
    static final Motion ANIMATED = new Motion(false);
    static final Motion INSTANT = new Motion(true);

    private final boolean animationsOff;

    private Motion(boolean animationsOff) {
        this.animationsOff = animationsOff;
    }

    static Motion of(LauncherSettings settings) {
        return settings.animationsOff ? INSTANT : ANIMATED;
    }

    /** Whether a focus/selection change (cursor, grow, dim, fades tied to focus) animates; {@code requested} is the caller's own wish. */
    boolean focusAnimated(boolean requested) {
        return requested && !animationsOff;
    }

    /** Whether backdrop/background changes crossfade. */
    boolean crossfade() {
        return !animationsOff;
    }

    /** Row and page scrolling always animates. */
    boolean scrollAnimated() {
        return true;
    }
}
