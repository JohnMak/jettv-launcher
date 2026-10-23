package com.johnmak.atvlauncher;

/**
 * A validated device shell command. Only the verbs of {@link Verb} exist; every argument is range-checked or a
 * typed value ({@link PackageName}, {@link GrantablePermission}), so no free-form string reaches the shell.
 * Factories live in {@link Gmpf} (`gmpfUnit` lamp/picture) {@link Pm} (package management) and {@link Stats} (read-only process stats).
 */
final class ShellCommand {
    /** Marker meaning the output must be empty. */
    static final String EMPTY = "";

    enum Verb {
        GET_DLP_LUMENS_LEVEL(null),
        GET_BRIGHTNESS(null),
        GET_CONTRAST(null),
        SET_DLP_LUMENS_LEVEL(null),
        SET_BRIGHTNESS(null),
        SET_CONTRAST(null),
        PM_GRANT(EMPTY),
        APPOPS_ALLOW_USAGE_STATS(EMPTY),
        ROLE_SET_HOME(EMPTY),
        PM_DISABLE("new state:"),
        PM_ENABLE("new state:"),
        PM_REMOVE_FOR_USER("Success"),
        PM_RESTORE("installed for user"),
        PM_UNINSTALL("Success"),
        PM_UNINSTALL_UPDATES("Success"),
        DUMPSYS_MEMINFO_COMPACT(null),
        PS_CPU_TIMES(null);

        private final String successMarker;

        Verb(String successMarker) { this.successMarker = successMarker; }

        /** Text a successful output must contain ({@link #EMPTY}: nothing at all); null for `gmpfUnit` and read-only stats verbs (judged by exit code). */
        String successMarker() { return successMarker; }

        boolean isPackageManagement() { return successMarker != null; }
    }

    /** The permissions the launcher may grant itself. */
    enum GrantablePermission {
        ACCESS_FINE_LOCATION, READ_TV_LISTINGS, WRITE_SECURE_SETTINGS
    }

    private final Verb verb;
    private final String line;

    private ShellCommand(Verb verb, String line) {
        this.verb = verb;
        this.line = line;
    }

    Verb verb() { return verb; }

    /** The exact line handed to the device shell. */
    String shellLine() { return line; }

    @Override public String toString() { return line; }

    /** `gmpfUnit` lamp and picture commands. */
    static final class Gmpf {
        static final int PICTURE_MIN = 0;
        static final int PICTURE_MAX = 100;

        private static final ShellCommand GET_LEVEL = new ShellCommand(Verb.GET_DLP_LUMENS_LEVEL, "gmpfUnit getDlpLumensLevel");
        private static final ShellCommand GET_BRIGHTNESS = new ShellCommand(Verb.GET_BRIGHTNESS, "gmpfUnit getBrightness 0");
        private static final ShellCommand GET_CONTRAST = new ShellCommand(Verb.GET_CONTRAST, "gmpfUnit getContrast 0");

        private Gmpf() {}

        static ShellCommand getLevel() { return GET_LEVEL; }

        static ShellCommand getBrightness() { return GET_BRIGHTNESS; }

        static ShellCommand getContrast() { return GET_CONTRAST; }

        /** The level type already guarantees 1..10. */
        static ShellCommand setLevel(LampLevel level) {
            return new ShellCommand(Verb.SET_DLP_LUMENS_LEVEL, "gmpfUnit setDlpLumensLevel " + level.value());
        }

        static FetchResult<ShellCommand> setBrightness(int value) {
            return picture(Verb.SET_BRIGHTNESS, "gmpfUnit setBrightness 0 ", value);
        }

        static FetchResult<ShellCommand> setContrast(int value) {
            return picture(Verb.SET_CONTRAST, "gmpfUnit setContrast 0 ", value);
        }

        private static FetchResult<ShellCommand> picture(Verb verb, String prefix, int value) {
            if (value < PICTURE_MIN || value > PICTURE_MAX) {
                return ShellError.fail(ShellError.COMMAND_FAILED,
                        verb + " must be " + PICTURE_MIN + ".." + PICTURE_MAX + ": " + value);
            }
            return FetchResult.ok(new ShellCommand(verb, prefix + value));
        }
    }

    /** Package-manager commands; the launcher's own id is passed in (BuildConfig.APPLICATION_ID), never free text. */
    static final class Pm {
        private Pm() {}

        static ShellCommand grant(PackageName own, GrantablePermission permission) {
            return new ShellCommand(Verb.PM_GRANT, "pm grant " + own + " android.permission." + permission.name());
        }

        static ShellCommand allowUsageStats(PackageName own) {
            return new ShellCommand(Verb.APPOPS_ALLOW_USAGE_STATS, "appops set " + own + " GET_USAGE_STATS allow");
        }

        static ShellCommand setHome(PackageName own) {
            return new ShellCommand(Verb.ROLE_SET_HOME, "cmd role add-role-holder --user 0 android.app.role.HOME " + own);
        }

        static ShellCommand disable(PackageName pkg) {
            return new ShellCommand(Verb.PM_DISABLE, "pm disable-user --user 0 " + pkg);
        }

        static ShellCommand enable(PackageName pkg) {
            return new ShellCommand(Verb.PM_ENABLE, "pm enable --user 0 " + pkg);
        }

        static ShellCommand removeForUser(PackageName pkg) {
            return new ShellCommand(Verb.PM_REMOVE_FOR_USER, "pm uninstall -k --user 0 " + pkg);
        }

        static ShellCommand restore(PackageName pkg) {
            return new ShellCommand(Verb.PM_RESTORE, "cmd package install-existing --user 0 " + pkg);
        }

        /** A user-installed app. */
        static ShellCommand uninstall(PackageName pkg) {
            return new ShellCommand(Verb.PM_UNINSTALL, "pm uninstall " + pkg);
        }

        /** Rolls an updated system app back to its factory version. */
        static ShellCommand uninstallUpdates(PackageName pkg) {
            return new ShellCommand(Verb.PM_UNINSTALL_UPDATES, "pm uninstall " + pkg);
        }
    }

    /** Read-only process statistics; success = exit 0 (classifier path, like `gmpfUnit`). */
    static final class Stats {
        private static final ShellCommand MEMINFO = new ShellCommand(Verb.DUMPSYS_MEMINFO_COMPACT, "dumpsys meminfo -c");
        private static final ShellCommand CPU_TIMES = new ShellCommand(Verb.PS_CPU_TIMES, "ps -A -o PID,TIME+,NAME");

        private Stats() {}

        static ShellCommand meminfoCompact() { return MEMINFO; }

        static ShellCommand cpuTimes() { return CPU_TIMES; }
    }
}
