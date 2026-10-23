package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure model of the redesigned cleanup screen: one list of installed apps sorted by storage, plus the block of removed system apps. */
final class CleanupList {
    private CleanupList() {}

    /** Row columns, in left-to-right order. */
    enum Col { TOGGLE, TRASH, ROLLBACK }

    /** Resource figures of one package; null = unknown. */
    static final class Stats {
        final Long sizeBytes;
        final Long pssKb;
        final Long cpuMillis;

        Stats(Long sizeBytes, Long pssKb, Long cpuMillis) {
            this.sizeBytes = sizeBytes;
            this.pssKb = pssKb;
            this.cpuMillis = cpuMillis;
        }
    }

    static final Stats NO_STATS = new Stats(null, null, null);

    /** Human description of a package. */
    interface Descriptions { String describe(String pkg, boolean system); }

    static final class Row {
        final String pkg;
        final String label;
        final String description;
        final boolean system;
        /** Checked state of the TOGGLE column. */
        final boolean enabled;
        /** Fully protected: listed greyed, no actions. */
        final boolean locked;
        final Set<Col> actions;
        final Stats stats;
        /** The profile's mark for this package, or null. */
        final CleanupProfile.Action recommended;

        Row(String pkg, String label, String description, boolean system, boolean enabled, boolean locked, Set<Col> actions,
            Stats stats, CleanupProfile.Action recommended) {
            this.pkg = pkg;
            this.label = label;
            this.description = description;
            this.system = system;
            this.enabled = enabled;
            this.locked = locked;
            this.actions = actions;
            this.stats = stats;
            this.recommended = recommended;
        }

        /** The operation a press on {@code col} performs; null when the column is not available. */
        CleanupSections.Op op(Col col) {
            if (!actions.contains(col)) return null;
            switch (col) {
                case TOGGLE: return enabled ? CleanupSections.Op.DISABLE : CleanupSections.Op.ENABLE;
                case TRASH: return system ? CleanupSections.Op.REMOVE_FOR_USER : CleanupSections.Op.UNINSTALL;
                default: return CleanupSections.Op.RESET;
            }
        }
    }

    /** A system app removed for the user; can be restored. */
    static final class Removed {
        final String pkg;
        final String label;
        final String description;
        /** Removed by this app (present in the cleanup log). */
        final boolean ours;

        Removed(String pkg, String label, String description, boolean ours) {
            this.pkg = pkg;
            this.label = label;
            this.description = description;
            this.ours = ours;
        }

        CleanupSections.Op op() { return CleanupSections.Op.RESTORE; }
    }

    static final class Result {
        /** Null when no profile matches this device. */
        final String profileName;
        final List<CleanupPlanner.Step> autoSteps;
        final List<Row> rows;
        final List<Removed> removed;

        Result(String profileName, List<CleanupPlanner.Step> autoSteps, List<Row> rows, List<Removed> removed) {
            this.profileName = profileName;
            this.autoSteps = autoSteps;
            this.rows = rows;
            this.removed = removed;
        }
    }

    /**
     * {@code baseProtected} = base + dynamic full protection. Home handlers: without the HOME role they are locked; with it
     * they are disable-only. Persistent apps are locked. {@code profile}, {@code stats} and {@code descriptions} may be null.
     */
    static Result build(List<CleanupSections.App> inventory, ProtectedPackages baseProtected, CleanupProfile profile,
                        CleanupLog log, boolean holdsHome, Map<String, Stats> stats, Descriptions descriptions) {
        List<String> homes = new ArrayList<>();
        for (CleanupSections.App a : inventory) if (a.homeHandler) homes.add(a.pkg);
        List<String> curated = new ArrayList<>();
        Map<String, CleanupProfile.Action> recommended = new HashMap<>();
        if (profile != null) {
            for (CleanupProfile.Entry e : profile.entries()) {
                curated.add(e.pkg);
                recommended.put(e.pkg, e.action);
            }
        }
        ProtectedPackages protectedSet = baseProtected.withDisableOnly(homes).allowingVendor(curated);
        Map<String, CleanupPlanner.PackageState> states = new HashMap<>();
        Map<String, CleanupSections.App> byPkg = new HashMap<>();
        for (CleanupSections.App a : inventory) {
            states.put(a.pkg, a.state);
            byPkg.put(a.pkg, a);
        }
        List<CleanupPlanner.Step> steps = new ArrayList<>();
        if (profile != null) {
            for (CleanupPlanner.Step s : CleanupPlanner.plan(profile, states, protectedSet, holdsHome, false)) {
                CleanupSections.App a = byPkg.get(s.pkg);
                if (a != null && a.homeHandler && !holdsHome) continue;
                steps.add(s);
            }
        }
        List<Row> rows = new ArrayList<>();
        List<Removed> removed = new ArrayList<>();
        for (CleanupSections.App a : inventory) {
            CleanupPlanner.PackageState s = a.state;
            String label = a.label == null || a.label.isEmpty() ? a.pkg : a.label;
            String desc = descriptions == null ? null : descriptions.describe(a.pkg, s.system);
            if (!s.installedForUser) {
                if (s.system) removed.add(new Removed(a.pkg, label, desc, log.get(a.pkg) != null));
                continue;
            }
            Stats st = stats == null || stats.get(a.pkg) == null ? NO_STATS : stats.get(a.pkg);
            ProtectedPackages.Level level = protectedSet.level(a.pkg);
            boolean locked = level == ProtectedPackages.Level.FULL || s.persistent || (a.homeHandler && !holdsHome);
            Set<Col> actions = EnumSet.noneOf(Col.class);
            if (locked && s.disabledUser) {
                locked = false; // already disabled: it can only be enabled again, never disabled or removed
                actions.add(Col.TOGGLE);
            } else if (!locked) {
                actions.add(Col.TOGGLE);
                if (level == ProtectedPackages.Level.NONE) actions.add(Col.TRASH);
                if (s.updatedSystem) actions.add(Col.ROLLBACK);
            }
            rows.add(new Row(a.pkg, label, desc, s.system, !s.disabledUser, locked, Collections.unmodifiableSet(actions), st,
                    recommended.get(a.pkg)));
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row x, Row y) {
                boolean kx = x.stats.sizeBytes != null;
                boolean ky = y.stats.sizeBytes != null;
                if (kx != ky) return kx ? -1 : 1;
                if (kx) {
                    int c = y.stats.sizeBytes.compareTo(x.stats.sizeBytes);
                    if (c != 0) return c;
                }
                int c = x.label.toLowerCase(Locale.ROOT).compareTo(y.label.toLowerCase(Locale.ROOT));
                return c != 0 ? c : x.pkg.compareTo(y.pkg);
            }
        });
        Collections.sort(removed, new Comparator<Removed>() {
            @Override public int compare(Removed x, Removed y) {
                int c = x.label.toLowerCase(Locale.ROOT).compareTo(y.label.toLowerCase(Locale.ROOT));
                return c != 0 ? c : x.pkg.compareTo(y.pkg);
            }
        });
        return new Result(profile == null ? null : profile.name(), Collections.unmodifiableList(steps), rows, removed);
    }
}
