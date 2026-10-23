package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class CleanupSectionsTest {
    private static CleanupSections.App app(String pkg, String label, boolean installed, boolean disabled, boolean system,
                                           boolean updated, boolean persistent, boolean home) {
        return new CleanupSections.App(pkg, label, new CleanupPlanner.PackageState(installed, disabled, system, updated, persistent), home);
    }

    private static CleanupSections.App user(String pkg, String label) { return app(pkg, label, true, false, false, false, false, false); }

    private static CleanupSections.App sys(String pkg, String label) { return app(pkg, label, true, false, true, false, false, false); }

    private static CleanupProfile profile(String body) {
        return CleanupProfile.parse("Test profile", "@match model=M\n" + body).value();
    }

    private static String ids(List<CleanupSections.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (CleanupSections.Item i : items) sb.append(i.pkg).append(':').append(i.op).append(i.recommended ? "*" : "").append(';');
        return sb.toString();
    }

    private static CleanupSections.Result build(List<CleanupSections.App> inv, CleanupProfile p, boolean holdsHome) {
        return CleanupSections.build(inv, ProtectedPackages.base().with(Collections.singleton("ime.keyboard")), p,
                CleanupLog.empty(), holdsHome);
    }

    @Test public void classifiesAndExcludesProtected() {
        List<CleanupSections.App> inv = Arrays.asList(
                user("u.zeta", "Zeta"), user("u.alpha", "alpha"),
                sys("s.bloat", "Bloat"), sys("com.google.android.gms", "GMS"), sys("ime.keyboard", "Keys"),
                app("s.persist", "Persist", true, false, true, false, true, false),
                app("s.updated", "Updated", true, false, true, true, false, false),
                app("s.off", "Off", true, true, true, false, false, false),
                app("s.gone", "Gone", false, false, true, false, false, false),
                app("u.gone", "UserGone", false, false, false, false, false, false));
        CleanupSections.Result r = build(inv, null, false);
        // sorted by label ignoring case; user -> uninstall, system -> remove for user
        assertEquals("u.alpha:UNINSTALL;s.bloat:REMOVE_FOR_USER;s.updated:REMOVE_FOR_USER;u.zeta:UNINSTALL;", ids(r.removable));
        assertEquals("s.bloat:DISABLE;s.updated:DISABLE;", ids(r.disable));
        assertEquals("s.updated:RESET;", ids(r.updates));
        assertEquals("s.gone:RESTORE;s.off:ENABLE;", ids(r.restore));
        assertNull(r.profileName);
        assertTrue(r.autoSteps.isEmpty());
    }

    @Test public void recommendedFirstWithMarker() {
        CleanupProfile p = profile("remove s.zzz\ndisable s.aaa\n");
        CleanupSections.Result r = build(Arrays.asList(sys("s.aaa", "AAA"), sys("s.zzz", "ZZZ"), sys("s.mid", "Mid")), p, false);
        assertEquals("s.zzz:REMOVE_FOR_USER*;s.aaa:REMOVE_FOR_USER;s.mid:REMOVE_FOR_USER;", ids(r.removable));
        assertEquals("s.aaa:DISABLE*;s.mid:DISABLE;s.zzz:DISABLE;", ids(r.disable));
        assertEquals("Test profile", r.profileName);
        assertEquals(2, r.autoSteps.size());
    }

    @Test public void homeHandlersFollowTheHomeRule() {
        List<CleanupSections.App> inv = Arrays.asList(app("stock.launcher", "Stock", true, false, true, true, false, true));
        CleanupSections.Result without = build(inv, null, false);
        assertTrue(without.removable.isEmpty() && without.disable.isEmpty() && without.updates.isEmpty());
        CleanupSections.Result with = build(inv, null, true);
        assertTrue(with.removable.isEmpty());
        assertEquals("stock.launcher:DISABLE;", ids(with.disable));
        assertEquals("stock.launcher:RESET;", ids(with.updates));
        // a stock launcher disabled while we hold HOME can still be restored
        CleanupSections.Result off = build(Arrays.asList(app("stock.launcher", "Stock", true, true, true, false, false, true)), null, false);
        assertEquals("stock.launcher:ENABLE;", ids(off.restore));
    }

    @Test public void autoStepsSkipHomeHandlersWithoutHome() {
        CleanupProfile p = profile("disable stock.launcher\ndisable a.b\n");
        List<CleanupSections.App> inv = Arrays.asList(app("stock.launcher", "Stock", true, false, true, false, false, true), sys("a.b", "AB"));
        assertEquals(1, build(inv, p, false).autoSteps.size());
        assertEquals(2, build(inv, p, true).autoSteps.size());
    }

    @Test public void restoreListsOurCleanupFirstAndLabelFallsBackToPackage() {
        CleanupLog log = CleanupLog.empty().record("s.ours", CleanupProfile.Action.DISABLE, 1L);
        List<CleanupSections.App> inv = Arrays.asList(
                app("s.aaa", "AAA", true, true, true, false, false, false),
                app("s.ours", "", false, false, true, false, false, false));
        CleanupSections.Result r = CleanupSections.build(inv, ProtectedPackages.base(), null, log, false);
        assertEquals("s.ours:RESTORE*;s.aaa:ENABLE;", ids(r.restore));
        assertEquals("s.ours", r.restore.get(0).label);
    }

    @Test public void opMapsToCommandAndLogEffect() {
        PackageName p = PackageName.parse("a.b").value();
        assertEquals("pm uninstall a.b", CleanupSections.Op.UNINSTALL.command(p).shellLine());
        assertEquals("pm uninstall -k --user 0 a.b", CleanupSections.Op.REMOVE_FOR_USER.command(p).shellLine());
        assertEquals("pm disable-user --user 0 a.b", CleanupSections.Op.DISABLE.command(p).shellLine());
        assertEquals("pm uninstall a.b", CleanupSections.Op.RESET.command(p).shellLine());
        assertEquals("pm enable --user 0 a.b", CleanupSections.Op.ENABLE.command(p).shellLine());
        assertEquals("cmd package install-existing --user 0 a.b", CleanupSections.Op.RESTORE.command(p).shellLine());
        assertEquals(CleanupProfile.Action.REMOVE, CleanupSections.Op.of(CleanupProfile.Action.REMOVE).logged());
        assertNull(CleanupSections.Op.UNINSTALL.logged());
        assertNull(CleanupSections.Op.ENABLE.logged());
    }

    @Test public void selectsProfileByModelOrProduct() {
        CleanupProfile a = CleanupProfile.parse("a", "@match model=X1\n").value();
        CleanupProfile b = CleanupProfile.parse("b", "@match product=prod\n").value();
        assertSame(b, CleanupSections.selectProfile(Arrays.asList(a, b), "nope", "prod"));
        assertSame(a, CleanupSections.selectProfile(Arrays.asList(a, b), "X1", "nope"));
        assertNull(CleanupSections.selectProfile(new ArrayList<CleanupProfile>(), "X1", "prod"));
    }

    @Test public void confirmNeedsSecondPressOnSameKey() {
        CleanupConfirm c = new CleanupConfirm();
        assertFalse(c.press("a"));
        assertTrue(c.isArmed("a"));
        assertFalse(c.press("b"));
        assertFalse(c.isArmed("a"));
        assertTrue(c.press("b"));
        assertFalse(c.isArmed("b"));
        assertFalse(c.press("b"));
        c.disarm();
        assertFalse(c.isArmed("b"));
    }

    @Test public void autoPlanNeverRemovesHomeHandlerAndVendorProfileEntryIsAllowed() {
        CleanupProfile p = profile("remove com.mitv.tvhome.atv\nremove com.xgimi.gimiplayer\n");
        List<CleanupSections.App> inv = Arrays.asList(
                app("com.mitv.tvhome.atv", "TV", true, false, true, false, false, true),
                sys("com.xgimi.gimiplayer", "Player"), sys("com.xgimi.unlisted", "Unlisted"));
        CleanupSections.Result r = build(inv, p, true);
        assertEquals(2, r.autoSteps.size());
        assertEquals(CleanupProfile.Action.DISABLE, r.autoSteps.get(0).action);
        assertEquals("com.mitv.tvhome.atv", r.autoSteps.get(0).pkg);
        assertEquals(CleanupProfile.Action.REMOVE, r.autoSteps.get(1).action);
        assertEquals("com.xgimi.gimiplayer", r.autoSteps.get(1).pkg);
        assertEquals("com.xgimi.gimiplayer:REMOVE_FOR_USER*;", ids(r.removable));
        assertEquals("com.xgimi.gimiplayer:DISABLE;com.mitv.tvhome.atv:DISABLE;", ids(r.disable).replace("*", ""));
    }
}
