package com.johnmak.atvlauncher;

/**
 * Animation policy from {@link LauncherSettings#animationsOff}: selection changes (cursor, grow, dim) become instant,
 * horizontal ribbon scrolling jumps, the vertical page shift and the backdrop crossfade always animate. Screens build it in onResume, so a changed setting applies on return.
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

    /** Horizontal ribbon scrolling animates only while the selection animation is on; otherwise rows jump. */
    boolean rowScrollAnimated() {
        return !animationsOff;
    }

    /** The vertical page shift always animates. */
    boolean pageScrollAnimated() {
        return true;
    }
}
