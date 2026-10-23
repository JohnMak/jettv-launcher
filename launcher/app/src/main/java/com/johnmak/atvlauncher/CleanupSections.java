package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure model of the system-cleanup screen: classifies the device inventory into the offered lists. */
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

    static final class Item {
        final String pkg;
        final String label;
        final Op op;
        final boolean recommended;

        Item(String pkg, String label, Op op, boolean recommended) {
            this.pkg = pkg;
            this.label = label;
            this.op = op;
            this.recommended = recommended;
        }
    }

    static final class Result {
        /** Null when no profile matches this device. */
        final String profileName;
        final List<CleanupPlanner.Step> autoSteps;
        final List<Item> removable;
        final List<Item> disable;
        final List<Item> updates;
        final List<Item> restore;

        Result(String profileName, List<CleanupPlanner.Step> autoSteps, List<Item> removable, List<Item> disable,
               List<Item> updates, List<Item> restore) {
            this.profileName = profileName;
            this.autoSteps = autoSteps;
            this.removable = removable;
            this.disable = disable;
            this.updates = updates;
            this.restore = restore;
        }
    }

    static CleanupProfile selectProfile(List<CleanupProfile> profiles, String model, String product) {
        for (CleanupProfile p : profiles) if (p.matches(model, product)) return p;
        return null;
    }

    /**
     * {@code protectedSet} = base + current IME; persistent apps are excluded here. Home handlers: without the HOME
     * role they are untouchable (only restorable); with it they can be disabled or reset but never removed.
     * {@code profile} may be null.
     */
    static Result build(List<App> inventory, ProtectedPackages protectedSet, CleanupProfile profile, CleanupLog log,
                        boolean holdsHome) {
        Map<String, CleanupProfile.Action> recommended = new HashMap<>();
        Map<String, CleanupPlanner.PackageState> states = new HashMap<>();
        Map<String, App> byPkg = new HashMap<>();
        if (profile != null) for (CleanupProfile.Entry e : profile.entries()) recommended.put(e.pkg, e.action);
        for (App a : inventory) {
            states.put(a.pkg, a.state);
            byPkg.put(a.pkg, a);
        }
        List<CleanupPlanner.Step> steps = new ArrayList<>();
        if (profile != null) {
            for (CleanupPlanner.Step s : CleanupPlanner.plan(profile, states, protectedSet, holdsHome, false)) {
                App a = byPkg.get(s.pkg);
                if (a != null && a.homeHandler && !holdsHome) continue;
                steps.add(s);
            }
        }
        List<Item> removable = new ArrayList<>();
        List<Item> disable = new ArrayList<>();
        List<Item> updates = new ArrayList<>();
        List<Item> restore = new ArrayList<>();
        for (App a : inventory) {
            CleanupPlanner.PackageState s = a.state;
            String label = a.label == null || a.label.isEmpty() ? a.pkg : a.label;
            if (s.system && (s.disabledUser || !s.installedForUser)) {
                restore.add(new Item(a.pkg, label, s.installedForUser ? Op.ENABLE : Op.RESTORE, log.get(a.pkg) != null));
                continue;
            }
            if (!s.installedForUser || s.persistent || protectedSet.contains(a.pkg) || (a.homeHandler && !holdsHome)) continue;
            CleanupProfile.Action rec = recommended.get(a.pkg);
            if (!a.homeHandler) {
                removable.add(new Item(a.pkg, label, s.system ? Op.REMOVE_FOR_USER : Op.UNINSTALL, rec == CleanupProfile.Action.REMOVE));
            }
            if (s.system) disable.add(new Item(a.pkg, label, Op.DISABLE, rec == CleanupProfile.Action.DISABLE));
            if (s.updatedSystem) updates.add(new Item(a.pkg, label, Op.RESET, rec == CleanupProfile.Action.RESET));
        }
        sort(removable);
        sort(disable);
        sort(updates);
        sort(restore);
        return new Result(profile == null ? null : profile.name(), Collections.unmodifiableList(steps), removable, disable, updates, restore);
    }

    /** Flagged items (recommended / ours) first, then by label ignoring case. */
    private static void sort(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            @Override public int compare(Item x, Item y) {
                if (x.recommended != y.recommended) return x.recommended ? -1 : 1;
                int c = x.label.toLowerCase(Locale.ROOT).compareTo(y.label.toLowerCase(Locale.ROOT));
                return c != 0 ? c : x.pkg.compareTo(y.pkg);
            }
        });
    }
}
