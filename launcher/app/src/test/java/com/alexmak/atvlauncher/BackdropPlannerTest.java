package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackdropPlannerTest {
    @Test
    public void nothingToDoWhileNoPosterWanted() {
        BackdropPlanner p = new BackdropPlanner();
        p.want(null, 0);
        assertFalse(p.ready(1000));
    }

    @Test
    public void posterIsCommittedOnlyAfterDebounce() {
        BackdropPlanner p = new BackdropPlanner();
        p.want("a", 1000);
        assertFalse(p.ready(1100));
        assertEquals(50, p.delayMs(1100));
        assertTrue(p.ready(1150));
        assertEquals("a", p.wanted());
        p.commit();
        assertFalse(p.ready(5000));
    }

    @Test
    public void rapidChangesRestartDebounceAndSameValueDoesNot() {
        BackdropPlanner p = new BackdropPlanner();
        p.want("a", 0);
        p.want("b", 100);
        assertFalse(p.ready(200));
        p.want("b", 240);
        assertTrue(p.ready(250));
    }

    @Test
    public void leavingFeedWantsNullAndReadyAfterDebounce() {
        BackdropPlanner p = new BackdropPlanner();
        p.want("a", 0);
        p.commit();
        p.want(null, 500);
        assertFalse(p.ready(550));
        assertTrue(p.ready(650));
        assertNull(p.wanted());
        p.commit();
        assertFalse(p.ready(900));
    }

    @Test
    public void returningToShownPosterNeedsNoWork() {
        BackdropPlanner p = new BackdropPlanner();
        p.want("a", 0);
        p.commit();
        p.want("b", 10);
        p.want("a", 20);
        assertFalse(p.ready(1000));
    }

    @Test
    public void afterForgetAppliedTheSameWantedKeyIsShownAgain() {
        BackdropPlanner p = new BackdropPlanner();
        p.want("a", 0);
        p.commit();
        assertFalse(p.ready(1000));
        // memory trim dropped the shown bitmap: the focused key must be rendered again
        p.forgetApplied();
        assertTrue(p.ready(1000));
    }
}
