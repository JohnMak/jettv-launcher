package com.alexmak.atvlauncher;

/**
 * A validated `gmpfUnit` shell command. Only the verbs of {@link Verb} exist and every argument is range-checked
 * by the factories, so no free-form string ever reaches the shell.
 */
final class GmpfCommand {
    enum Verb {
        GET_DLP_LUMENS_LEVEL,
        GET_BRIGHTNESS,
        GET_CONTRAST,
        SET_DLP_LUMENS_LEVEL,
        SET_BRIGHTNESS,
        SET_CONTRAST
    }

    static final int PICTURE_MIN = 0;
    static final int PICTURE_MAX = 100;

    private static final GmpfCommand GET_LEVEL = new GmpfCommand(Verb.GET_DLP_LUMENS_LEVEL, "gmpfUnit getDlpLumensLevel");
    private static final GmpfCommand GET_BRIGHTNESS = new GmpfCommand(Verb.GET_BRIGHTNESS, "gmpfUnit getBrightness 0");
    private static final GmpfCommand GET_CONTRAST = new GmpfCommand(Verb.GET_CONTRAST, "gmpfUnit getContrast 0");

    private final Verb verb;
    private final String line;

    private GmpfCommand(Verb verb, String line) {
        this.verb = verb;
        this.line = line;
    }

    static GmpfCommand getLevel() { return GET_LEVEL; }

    static GmpfCommand getBrightness() { return GET_BRIGHTNESS; }

    static GmpfCommand getContrast() { return GET_CONTRAST; }

    /** The level type already guarantees 1..10. */
    static GmpfCommand setLevel(LampLevel level) {
        return new GmpfCommand(Verb.SET_DLP_LUMENS_LEVEL, "gmpfUnit setDlpLumensLevel " + level.value());
    }

    static FetchResult<GmpfCommand> setBrightness(int value) {
        return picture(Verb.SET_BRIGHTNESS, "gmpfUnit setBrightness 0 ", value);
    }

    static FetchResult<GmpfCommand> setContrast(int value) {
        return picture(Verb.SET_CONTRAST, "gmpfUnit setContrast 0 ", value);
    }

    private static FetchResult<GmpfCommand> picture(Verb verb, String prefix, int value) {
        if (value < PICTURE_MIN || value > PICTURE_MAX) {
            return LampError.fail(LampError.COMMAND_FAILED,
                    verb + " must be " + PICTURE_MIN + ".." + PICTURE_MAX + ": " + value);
        }
        return FetchResult.ok(new GmpfCommand(verb, prefix + value));
    }

    Verb verb() { return verb; }

    /** The exact line handed to the device shell. */
    String shellLine() { return line; }

    @Override public String toString() { return line; }
}
