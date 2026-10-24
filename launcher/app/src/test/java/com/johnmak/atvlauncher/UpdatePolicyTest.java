package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
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

    @Test public void tickChecksOnlyAfterSixHours() {
        assertFalse(check(UpdateState.Kind.UP_TO_DATE, UpdatePolicy.Trigger.TICK, NOW - 6 * H + 1));
        assertTrue(check(UpdateState.Kind.UP_TO_DATE, UpdatePolicy.Trigger.TICK, NOW - 6 * H));
        assertTrue(check(UpdateState.Kind.IDLE, UpdatePolicy.Trigger.TICK, 0)); // never checked
        assertTrue(check(UpdateState.Kind.IDLE, UpdatePolicy.Trigger.TICK, NOW + H)); // clock went back
    }

    @Test public void noCheckWhileBusyOrHoldingAnUpdate() {
        for (UpdateState.Kind k : new UpdateState.Kind[]{UpdateState.Kind.CHECKING, UpdateState.Kind.DOWNLOADING,
                UpdateState.Kind.VERIFYING, UpdateState.Kind.READY, UpdateState.Kind.INSTALLING}) {
            assertFalse(k.name(), check(k, UpdatePolicy.Trigger.PROCESS_START, 0));
        }
    }

    @Test public void intervalIsSixHours() {
        assertEquals(6 * H, UpdatePolicy.CHECK_INTERVAL_MS);
    }

    @Test public void autoInstallNeedsReadyAndEitherIdleForegroundOrScreensaver() {
        long ten = UpdatePolicy.AUTO_INSTALL_IDLE_MS;
        assertTrue(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, true, ten, false));
        assertFalse(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, true, ten - 1, false));
        assertFalse(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, false, ten * 5, false));
        // a screensaver (in-launcher or system dream) opens the window regardless of foreground and idle time
        assertTrue(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, false, 0, true));
        assertTrue(UpdatePolicy.shouldAutoInstall(UpdateState.Kind.READY, true, 0, true));
        for (UpdateState.Kind k : new UpdateState.Kind[]{UpdateState.Kind.VERIFYING, UpdateState.Kind.INSTALLING,
                UpdateState.Kind.DOWNLOADING, UpdateState.Kind.IDLE}) {
            assertFalse(k.name(), UpdatePolicy.shouldAutoInstall(k, true, ten * 5, true));
        }
    }

    @Test public void bootWindowDecisions() {
        long w = UpdatePolicy.BOOT_WINDOW_MS;
        assertTrue(UpdatePolicy.isBootStart(0));
        assertTrue(UpdatePolicy.isBootStart(w - 1));
        assertFalse(UpdatePolicy.isBootStart(w));
        assertTrue(UpdatePolicy.bootActive(NOW, NOW + 1));
        assertFalse(UpdatePolicy.bootActive(NOW, NOW));
        assertFalse(UpdatePolicy.bootActive(NOW, 0));
        assertTrue(check(UpdateState.Kind.UP_TO_DATE, UpdatePolicy.Trigger.BOOT, NOW));
        assertTrue(check(UpdateState.Kind.ERROR, UpdatePolicy.Trigger.BOOT, NOW));
        assertFalse(check(UpdateState.Kind.READY, UpdatePolicy.Trigger.BOOT, 0));
    }

    @Test public void bootRetriesOnlyNetworkFailures() {
        assertFalse(UpdatePolicy.bootAnswered(UpdateState.Kind.ERROR, UpdateError.NETWORK));
        assertFalse(UpdatePolicy.bootAnswered(UpdateState.Kind.CHECKING, null));
        assertTrue(UpdatePolicy.bootAnswered(UpdateState.Kind.ERROR, UpdateError.FEED_INVALID));
        assertTrue(UpdatePolicy.bootAnswered(UpdateState.Kind.UP_TO_DATE, null));
        assertTrue(UpdatePolicy.bootAnswered(UpdateState.Kind.INSTALLING, null));
    }

    @Test public void retryIntervalIs30sDuringBootElseTenMinutes() {
        assertEquals(30_000L, UpdatePolicy.nextTickDelayMs(true));
        assertEquals(10 * 60_000L, UpdatePolicy.nextTickDelayMs(false));
    }
}
