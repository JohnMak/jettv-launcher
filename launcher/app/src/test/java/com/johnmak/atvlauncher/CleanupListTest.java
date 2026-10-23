package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CleanupListTest {
    private static CleanupSections.App app(String pkg, String label, boolean installed, boolean disabled, boolean system,
                                           boolean updated, boolean persistent, boolean home) {
        return new CleanupSections.App(pkg, label, new CleanupPlanner.PackageState(installed, disabled, system, updated, persistent), home);
    }

    private static CleanupSections.App user(String pkg, String label) { return app(pkg, label, true, false, false, false, false, false); }

    private static CleanupSections.App sys(String pkg, String label) { return app(pkg, label, true, false, true, false, false, false); }

    private static CleanupList.Result build(List<CleanupSections.App> inv, CleanupProfile p, boolean holdsHome,
                                            Map<String, CleanupList.Stats> stats) {
        return CleanupList.build(inv, ProtectedPackages.base().with(Collections.singleton("ime.keyboard")), p,
                CleanupLog.empty(), holdsHome, stats, new CleanupList.Descriptions() {
                    @Override public String describe(String pkg, boolean system) { return pkg + (system ? "/sys" : "/usr"); }
                });
    }

    private static CleanupList.Row row(CleanupList.Result r, String pkg) {
        for (CleanupList.Row x : r.rows) if (x.pkg.equals(pkg)) return x;
        return null;
    }

    private static String pkgs(CleanupList.Result r) {
        StringBuilder sb = new StringBuilder();
        for (CleanupList.Row x : r.rows) sb.append(x.pkg).append(',');
        return sb.toString();
    }

    private static CleanupList.Stats size(long b) { return new CleanupList.Stats(b, null, null); }

    @Test public void classifiesActionsAndOps() {
        CleanupList.Result r = build(Arrays.asList(
                user("u.a", "A"), sys("s.b", "B"), sys("com.google.android.gms", "GMS"), sys("ime.keyboard", "Keys"),
                app("s.persist", "Persist", true, false, true, false, true, false),
                app("s.updated", "Updated", true, false, true, true, false, false),
                app("s.off", "Off", true, true, true, false, false, false),
                app("s.gone", "Gone", false, false, true, false, false, false),
                app("u.gone", "UserGone", false, false, false, false, false, false)), null, false, null);
        assertEquals("[TOGGLE, TRASH]", row(r, "u.a").actions.toString());
        assertEquals(CleanupSections.Op.UNINSTALL, row(r, "u.a").op(CleanupList.Col.TRASH));
        assertEquals(CleanupSections.Op.REMOVE_FOR_USER, row(r, "s.b").op(CleanupList.Col.TRASH));
        assertEquals(CleanupSections.Op.DISABLE, row(r, "s.b").op(CleanupList.Col.TOGGLE));
        assertNull(row(r, "s.b").op(CleanupList.Col.ROLLBACK));
        assertEquals("[TOGGLE, TRASH, ROLLBACK]", row(r, "s.updated").actions.toString());
        assertEquals(CleanupSections.Op.RESET, row(r, "s.updated").op(CleanupList.Col.ROLLBACK));
        assertNull(row(r, "u.a").op(CleanupList.Col.ROLLBACK));
        assertFalse(row(r, "s.off").enabled);
        assertEquals(CleanupSections.Op.ENABLE, row(r, "s.off").op(CleanupList.Col.TOGGLE));
        assertEquals("s.b/sys", row(r, "s.b").description);
        // fully protected and persistent: listed, locked, no actions
        for (String p : new String[] {"com.google.android.gms", "ime.keyboard", "s.persist"}) {
            assertTrue(p, row(r, p).locked);
            assertTrue(p, row(r, p).actions.isEmpty());
        }
        // a disabled locked app can only be enabled again: toggle offered, never trash/rollback, op is ENABLE
        CleanupList.Result off = build(Arrays.asList(
                app("s.persist.off", "PersistOff", true, true, true, false, true, false),
                app("com.google.android.gms", "GMS", true, true, true, false, false, false)), null, false, null);
        for (String p : new String[] {"s.persist.off", "com.google.android.gms"}) {
            assertEquals(p, "[TOGGLE]", row(off, p).actions.toString());
            assertEquals(p, CleanupSections.Op.ENABLE, row(off, p).op(CleanupList.Col.TOGGLE));
            assertFalse(p, row(off, p).enabled);
        }
        // removed block: only system apps not installed for the user
        assertEquals(1, r.removed.size());
        assertEquals("s.gone", r.removed.get(0).pkg);
        assertEquals(CleanupSections.Op.RESTORE, r.removed.get(0).op());
        assertNull(row(r, "s.gone"));
        assertNull(row(r, "u.gone"));
        assertNull(r.profileName);
        assertTrue(r.autoSteps.isEmpty());
    }

    @Test public void removedMarksOursFromLog() {
        CleanupLog log = CleanupLog.empty().record("s.gone", CleanupProfile.Action.REMOVE, 1L);
        CleanupList.Result r = CleanupList.build(Arrays.asList(app("s.gone", "Gone", false, false, true, false, false, false),
                app("s.other", "Alpha", false, false, true, false, false, false)), ProtectedPackages.base(), null, log, false, null, null);
        assertEquals("s.other", r.removed.get(0).pkg);
        assertFalse(r.removed.get(0).ours);
        assertTrue(r.removed.get(1).ours);
        assertNull(r.removed.get(0).description);
    }

    @Test public void homeHandlerRules() {
        List<CleanupSections.App> inv = Arrays.asList(app("h.stock", "Stock", true, false, true, false, false, true));
        CleanupList.Row without = row(build(inv, null, false, null), "h.stock");
        assertTrue(without.locked);
        assertTrue(without.actions.isEmpty());
        CleanupList.Row with = row(build(inv, null, true, null), "h.stock");
        assertFalse(with.locked);
        assertEquals("[TOGGLE]", with.actions.toString());
        assertNull(with.op(CleanupList.Col.TRASH));
    }

    @Test public void homeHandlerWithRoleKeepsRollback() {
        List<CleanupSections.App> inv = Arrays.asList(app("h.stock", "Stock", true, false, true, true, false, true));
        assertEquals("[TOGGLE, ROLLBACK]", row(build(inv, null, true, null), "h.stock").actions.toString());
    }

    @Test public void sortsBySizeDescUnknownLastThenLabel() {
        Map<String, CleanupList.Stats> st = new HashMap<>();
        st.put("a", size(10));
        st.put("b", size(300));
        st.put("c", size(10));
        st.put("e", new CleanupList.Stats(null, 5L, null));
        CleanupList.Result r = build(Arrays.asList(user("d", "Delta"), user("c", "Charlie"), user("a", "alpha"),
                user("b", "Bravo"), user("e", "Echo"), user("f", "Able")), null, false, st);
        assertEquals("b,a,c,f,d,e,", pkgs(r));
    }

    @Test public void profileMarksAndAutoSteps() {
        CleanupProfile p = CleanupProfile.parse("Test profile", "@match model=M\nremove s.zzz\ndisable s.aaa\n").value();
        CleanupList.Result r = build(Arrays.asList(sys("s.aaa", "AAA"), sys("s.zzz", "ZZZ"), sys("s.mid", "Mid")), p, false, null);
        assertEquals(CleanupProfile.Action.REMOVE, row(r, "s.zzz").recommended);
        assertEquals(CleanupProfile.Action.DISABLE, row(r, "s.aaa").recommended);
        assertNull(row(r, "s.mid").recommended);
        assertEquals("Test profile", r.profileName);
        assertEquals(2, r.autoSteps.size());
    }

    @Test public void vendorPackageListedByProfileIsActionable() {
        CleanupProfile p = CleanupProfile.parse("P", "@match model=M\nremove com.xgimi.bloat\n").value();
        CleanupList.Result r = build(Arrays.asList(sys("com.xgimi.bloat", "Bloat"), sys("com.xgimi.core", "Core")), p, false, null);
        assertFalse(row(r, "com.xgimi.bloat").locked);
        assertTrue(row(r, "com.xgimi.core").locked);
    }
}
