package com.alexmak.atvlauncher;

/** Closed whitelist of `gmpfUnit` shell commands; no arbitrary strings can be built. */
final class GmpfCommands {
    static final int MODE_CUSTOM = 6;

    static final String GET_MODE = "gmpfUnit getDlpLumensMode";
    static final String GET_LEVEL = "gmpfUnit getDlpLumensLevel";
    static final String GET_BRIGHTNESS = "gmpfUnit getBrightness 0";
    static final String GET_CONTRAST = "gmpfUnit getContrast 0";
    static final int PICTURE_MIN = 0;
    static final int PICTURE_MAX = 100;
    static final String SET_MODE_CUSTOM = "gmpfUnit setDlpLumensMode " + MODE_CUSTOM;

    private GmpfCommands() {}

    static String setLevel(LampLevel level) {
        return "gmpfUnit setDlpLumensLevel " + level.value();
    }

    static String setBrightness(int value) {
        return "gmpfUnit setBrightness 0 " + checkedPicture(value);
    }

    static String setContrast(int value) {
        return "gmpfUnit setContrast 0 " + checkedPicture(value);
    }

    private static int checkedPicture(int value) {
        if (value < PICTURE_MIN || value > PICTURE_MAX) {
            throw new IllegalArgumentException("picture value must be 0..100: " + value);
        }
        return value;
    }

    private static final java.util.regex.Pattern VALID =
            java.util.regex.Pattern.compile("gmpfUnit [A-Za-z]+( [0-9]{1,3}| 0 [0-9]{1,3})?");

    /** True only for `gmpfUnit <Name>`, `gmpfUnit <Name> <N>` or `gmpfUnit <Name> 0 <N>`: no shell metacharacters can pass. */
    static boolean isValid(String command) {
        return command != null && VALID.matcher(command).matches();
    }
}
