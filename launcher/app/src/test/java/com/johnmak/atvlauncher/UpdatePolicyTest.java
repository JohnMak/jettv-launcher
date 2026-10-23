package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdatePolicyTest {
    private static final long H = 60 * 60 * 1000L;
    private static final long NOW = 1_000 * H;

    private static boolean check(UpdateState.Kind k, UpdatePolicy.Trigger t, long last) {
        return UpdatePolicy.shouldCheck(k, t, last, NOW);
    }

    @Test public void processStartAlwaysChecksWhenNothingIsInFlight() {
        assertTrue(check(UpdateState.Kind.IDLE, UpdatePolicy.Trigger.PROCESS_START, NOW - 1000));
        assertTrue(check(UpdateState.Kind.ERROR, UpdatePolicy.Trigger.PROCESS_START, NOW));
    }

    @Test public void tickChecksOnlyAfterTwentyFourHours() {
        assertFalse(check(UpdateState.Kind.UP_TO_DATE, UpdatePolicy.Trigger.TICK, NOW - 24 * H + 1));
        assertTrue(check(UpdateState.Kind.UP_TO_DATE, UpdatePolicy.Trigger.TICK, NOW - 24 * H));
        assertTrue(check(UpdateState.Kind.IDLE, UpdatePolicy.Trigger.TICK, 0)); // never checked
        assertTrue(check(UpdateState.Kind.IDLE, UpdatePolicy.Trigger.TICK, NOW + H)); // clock went back
    }

    @Test public void noCheckWhileBusyOrHoldingAnUpdate() {
        for (UpdateState.Kind k : new UpdateState.Kind[]{UpdateState.Kind.CHECKING, UpdateState.Kind.DOWNLOADING,
                UpdateState.Kind.VERIFYING, UpdateState.Kind.READY, UpdateState.Kind.INSTALLING}) {
            assertFalse(k.name(), check(k, UpdatePolicy.Trigger.PROCESS_START, 0));
        }
    }

    @Test public void autoInstallNeedsReadyForegroundAndTenIdleMinutes() {
        long ten = UpdatePolicy.AUTO_INSTALL_IDLE_MS;
        assertTrue(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, true, ten));
        assertFalse(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, true, ten - 1));
        assertFalse(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, false, ten * 5));
        assertFalse(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.VERIFYING, true, ten * 5));
        assertFalse(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.INSTALLING, true, ten * 5));
    }
}
