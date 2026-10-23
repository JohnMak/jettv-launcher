package com.johnmak.atvlauncher;

import java.util.List;

/** Building blocks of the system-cleanup screen: package inventory entries, the shell operation per action and profile selection. */
final class CleanupSections {
    private CleanupSections() {}

    /** What can be done to one package; maps to exactly one shell command. */
    enum Op {
        UNINSTALL, REMOVE_FOR_USER, DISABLE, RESET, ENABLE, RESTORE;

        static Op of(CleanupProfile.Action action) {
            switch (action) {
                case REMOVE: return REMOVE_FOR_USER;
                case RESET: return RESET;
                default: return DISABLE;
            }
        }

        ShellCommand command(PackageName pkg) {
            switch (this) {
                case UNINSTALL: return ShellCommand.Pm.uninstall(pkg);
                case REMOVE_FOR_USER: return ShellCommand.Pm.removeForUser(pkg);
                case DISABLE: return ShellCommand.Pm.disable(pkg);
                case RESET: return ShellCommand.Pm.uninstallUpdates(pkg);
                case ENABLE: return ShellCommand.Pm.enable(pkg);
                default: return ShellCommand.Pm.restore(pkg);
            }
        }

        /** The cleanup-log action recorded after success; null when nothing is recorded (undo ops drop the entry). */
        CleanupProfile.Action logged() {
            switch (this) {
                case REMOVE_FOR_USER: return CleanupProfile.Action.REMOVE;
                case DISABLE: return CleanupProfile.Action.DISABLE;
                case RESET: return CleanupProfile.Action.RESET;
                default: return null;
            }
        }

        boolean isUndo() { return this == ENABLE || this == RESTORE; }
    }

    static final class App {
        final String pkg;
        final String label;
        final CleanupPlanner.PackageState state;
        /** Handles the HOME intent (a launcher). */
        final boolean homeHandler;

        App(String pkg, String label, CleanupPlanner.PackageState state, boolean homeHandler) {
            this.pkg = pkg;
            this.label = label;
            this.state = state;
            this.homeHandler = homeHandler;
        }
    }

    static CleanupProfile selectProfile(List<CleanupProfile> profiles, String model, String product) {
        for (CleanupProfile p : profiles) if (p.matches(model, product)) return p;
        return null;
    }
}
