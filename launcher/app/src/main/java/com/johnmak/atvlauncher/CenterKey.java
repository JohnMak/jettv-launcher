package com.johnmak.atvlauncher;

/**
 * Tells a short OK press from a long one. A long press fires once, on the first key repeat;
 * a short press fires on key up, and only if no long press happened in between.
 */
final class CenterKey {
    enum Press { NONE, SHORT, LONG }

    private boolean held;
    private boolean longFired;

    /** Feed every DOWN (with its repeat count) and UP event of the OK key; returns what to act on now. */
    Press onKey(boolean down, int repeatCount) {
        if (down) {
            if (repeatCount == 0) {
                held = true;
                longFired = false;
            } else if (held && !longFired) {
                longFired = true;
                return Press.LONG;
            }
            return Press.NONE;
        }
        boolean shortPress = held && !longFired;
        held = false;
        return shortPress ? Press.SHORT : Press.NONE;
    }
}
