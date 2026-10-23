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

    private static final java.util.regex.Pattern VALID =
            java.util.regex.Pattern.compile("gmpfUnit [A-Za-z]+( [0-9]{1,3})?");

    /** True only for `gmpfUnit <Name>` or `gmpfUnit <Name> <number>`: no shell metacharacters can pass. */
    static boolean isValid(String command) {
        return command != null && VALID.matcher(command).matches();
    }
}
