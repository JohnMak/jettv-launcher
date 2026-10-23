package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class CleanupSectionsTest {
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
}
