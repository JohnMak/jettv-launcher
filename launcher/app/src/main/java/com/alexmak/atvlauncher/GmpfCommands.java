package com.alexmak.atvlauncher;

/** Closed whitelist of `gmpfUnit` shell commands; no arbitrary strings can be built. */
final class GmpfCommands {
    static final int MODE_CUSTOM = 6;

    static final String GET_MODE = "gmpfUnit getDlpLumensMode";
    static final String GET_LEVEL = "gmpfUnit getDlpLumensLevel";
    static final String SET_MODE_CUSTOM = "gmpfUnit setDlpLumensMode " + MODE_CUSTOM;

    private GmpfCommands() {}

    static String setLevel(LampLevel level) {
        return "gmpfUnit setDlpLumensLevel " + level.value();
    }
}
