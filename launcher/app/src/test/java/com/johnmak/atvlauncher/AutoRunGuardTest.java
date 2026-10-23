package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class AutoRunGuardTest {
    private static CleanupPlanner.Step step(String pkg) { return new CleanupPlanner.Step(CleanupProfile.Action.DISABLE, pkg); }

    @Test
    public void secondPressExecutesExactlyTheFirstPressSnapshot() {
        CleanupConfirm c = new CleanupConfirm();
        AutoRunGuard g = new AutoRunGuard(c);
        List<CleanupPlanner.Step> first = new ArrayList<>(Arrays.asList(step("a"), step("b")));
        assertNull(g.press(first));
        assertTrue(g.isArmed());
        first.add(step("c")); // caller list mutated after arming: snapshot is independent
        List<CleanupPlanner.Step> run = g.press(first);
        assertEquals(2, run.size());
        assertEquals("a", run.get(0).pkg);
        assertFalse(g.isArmed());
    }

    @Test
    public void changedListDisarms() {
        CleanupConfirm c = new CleanupConfirm();
        AutoRunGuard g = new AutoRunGuard(c);
        List<CleanupPlanner.Step> a = Arrays.asList(step("a"), step("b"));
        assertNull(g.press(a));
        g.sync(Arrays.asList(step("a"), step("b"))); // same content: stays armed
        assertTrue(g.isArmed());
        g.sync(Arrays.asList(step("a")));
        assertFalse(g.isArmed());
        assertFalse(c.isArmed("auto"));
        assertNull(g.press(a)); // arms again instead of running
    }

    @Test
    public void otherKeyReArmingDropsTheSnapshot() {
        CleanupConfirm c = new CleanupConfirm();
        AutoRunGuard g = new AutoRunGuard(c);
        List<CleanupPlanner.Step> a = Arrays.asList(step("a"));
        assertNull(g.press(a));
        c.press("TOGGLE:x");
        g.sync(a);
        assertFalse(g.isArmed());
        assertNull(g.press(a));
    }
}
