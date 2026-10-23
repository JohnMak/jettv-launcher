package com.johnmak.atvlauncher;

import android.view.View;

/**
 * The one brightness rule of the home screen (OLED friendly): everything unfocused is dimmed, only the focused
 * element is at full brightness. Pure levels here; {@link DimImageView} applies the tile level to pictures and
 * {@link #applyAlpha} applies the text level to any view.
 */
final class FocusDim {
    static final long DURATION_MS = 150;

    /** What kind of element is dimmed; each kind has its own unfocused level. */
    enum Role {
        /** Captions, tile names. */
        TEXT(0.8f),
        /** Top bar items (Wi-Fi, VPN, gear) and the weather item. */
        TOP_BAR(0.55f),
        /** Clock and date: never focused, always at their dimmed level. */
        AMBIENT(0.75f),
        /** App tiles and feed cards (colour brightness, not transparency). */
        TILE(0.75f);

        final float unfocused;

        Role(float unfocused) {
            this.unfocused = unfocused;
        }
    }

    private FocusDim() {
    }

    /** Brightness / opacity (0..1) of an element of {@code role} in the given focus state. */
    static float level(Role role, boolean focused) {
        return focused ? 1f : role.unfocused;
    }

    static void applyAlpha(View v, Role role, boolean focused, boolean animate) {
        float a = level(role, focused);
        v.animate().cancel();
        if (animate) {
            v.animate().alpha(a).setDuration(DURATION_MS).start();
        } else {
            v.setAlpha(a);
        }
    }
}
