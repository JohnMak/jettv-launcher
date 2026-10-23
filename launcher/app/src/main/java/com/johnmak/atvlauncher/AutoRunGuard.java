package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Two-press confirmation of the automatic run that executes exactly what the first press showed: the armed state holds a
 * snapshot of the step list; a rebuild that changes the list disarms it.
 */
final class AutoRunGuard {
    static final String KEY = "auto";

    private final CleanupConfirm confirm;
    private List<CleanupPlanner.Step> armed;

    AutoRunGuard(CleanupConfirm confirm) { this.confirm = confirm; }

    /** First press: snapshots {@code current}, returns null. Second press: returns the snapshot to execute. */
    List<CleanupPlanner.Step> press(List<CleanupPlanner.Step> current) {
        boolean confirmed = confirm.press(KEY);
        if (confirmed && armed != null) {
            List<CleanupPlanner.Step> run = armed;
            armed = null;
            return run;
        }
        if (confirmed) confirm.press(KEY); // confirmed without a snapshot: arm again
        armed = Collections.unmodifiableList(new ArrayList<>(current));
        return null;
    }

    boolean isArmed() { return armed != null; }

    /** Call after the model is rebuilt: drops the snapshot when another key took over or the list changed. */
    void sync(List<CleanupPlanner.Step> current) {
        if (armed == null) return;
        if (confirm.isArmed(KEY) && same(armed, current)) return;
        if (confirm.isArmed(KEY)) confirm.disarm();
        armed = null;
    }

    private static boolean same(List<CleanupPlanner.Step> a, List<CleanupPlanner.Step> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).action != b.get(i).action || !a.get(i).pkg.equals(b.get(i).pkg)) return false;
        }
        return true;
    }
}
