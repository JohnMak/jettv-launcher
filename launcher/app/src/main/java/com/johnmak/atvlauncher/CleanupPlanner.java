package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Turns a profile and a device inventory into the ordered list of cleanup actions still needed. */
final class CleanupPlanner {
    private CleanupPlanner() {}

    /** What the device reports about one package for user 0. */
    static final class PackageState {
        final boolean installedForUser;
        final boolean disabledUser;
        final boolean system;
        final boolean updatedSystem;
        final boolean persistent;

        PackageState(boolean installedForUser, boolean disabledUser, boolean system, boolean updatedSystem, boolean persistent) {
            this.installedForUser = installedForUser;
            this.disabledUser = disabledUser;
            this.system = system;
            this.updatedSystem = updatedSystem;
            this.persistent = persistent;
        }
    }

    static final class Step {
        final CleanupProfile.Action action;
        final String pkg;

        Step(CleanupProfile.Action action, String pkg) {
            this.action = action;
            this.pkg = pkg;
        }
    }

    /**
     * Steps in profile order with {@code requires=home} entries (stock launchers) last. Skipped: optional entries
     * (unless {@code includeOptional}), requires=home entries without the HOME role, protected or persistent or absent
     * packages, and work already done (disabled / not installed for the user / reset without updates).
     */
    static List<Step> plan(CleanupProfile profile, Map<String, PackageState> inventory, ProtectedPackages protectedSet,
                           boolean holdsHome, boolean includeOptional) {
        List<Step> regular = new ArrayList<>();
        List<Step> launchers = new ArrayList<>();
        for (CleanupProfile.Entry e : profile.entries()) {
            if (e.optional && !includeOptional) continue;
            if (e.requiresHome && !holdsHome) continue;
            if (protectedSet.contains(e.pkg)) continue;
            PackageState s = inventory.get(e.pkg);
            if (s == null || !s.installedForUser || s.persistent) continue;
            if (e.action == CleanupProfile.Action.DISABLE && s.disabledUser) continue;
            if (e.action == CleanupProfile.Action.RESET && !s.updatedSystem) continue;
            (e.requiresHome ? launchers : regular).add(new Step(e.action, e.pkg));
        }
        regular.addAll(launchers);
        return Collections.unmodifiableList(regular);
    }
}
