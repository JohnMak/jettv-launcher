package com.alexmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class LauncherSettingsTest {
    @Test public void defaults() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertTrue(s.screensaverEnabled);
        assertEquals(2, s.screensaverDelayMin);
        assertTrue(s.dimEnabled);
        assertEquals(30, s.dimDelaySec);
        assertEquals(120_000L, s.screensaverDelayMillis());
        assertEquals(30_000L, s.dimDelayMillis());
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, s.screensaverType);
        assertTrue(s.vpnButtonVisible);
        assertEquals("", s.vpnPackage);
        assertEquals(100, s.uiScalePercent);
        assertEquals(100, s.previewScalePercent);
    }

    @Test public void screensaverDelayCyclesThroughOptionsAndWraps() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertEquals(3, s.nextScreensaverDelay().screensaverDelayMin);
        assertEquals(5, s.nextScreensaverDelay().nextScreensaverDelay().screensaverDelayMin);
        assertEquals(1, s.prevScreensaverDelay().screensaverDelayMin);
        assertEquals(10, s.prevScreensaverDelay().prevScreensaverDelay().screensaverDelayMin);
        LauncherSettings last = s.prevScreensaverDelay().prevScreensaverDelay();
        assertEquals(1, last.nextScreensaverDelay().screensaverDelayMin);
    }

    @Test public void dimDelayCyclesThroughOptionsAndWraps() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertEquals(60, s.nextDimDelay().dimDelaySec);
        assertEquals(15, s.prevDimDelay().dimDelaySec);
        assertEquals(120, s.prevDimDelay().prevDimDelay().dimDelaySec);
        assertEquals(15, s.nextDimDelay().nextDimDelay().nextDimDelay().dimDelaySec);
    }

    @Test public void togglesKeepTheRest() {
        LauncherSettings s = LauncherSettings.DEFAULTS.withScreensaverEnabled(false).withDimEnabled(false);
        assertFalse(s.screensaverEnabled);
        assertFalse(s.dimEnabled);
        assertEquals(2, s.screensaverDelayMin);
        assertEquals(30, s.dimDelaySec);
        LauncherSettings v = s.withVpnButtonVisible(false).withVpnPackage("a.b");
        assertFalse(v.vpnButtonVisible);
        assertEquals("a.b", v.vpnPackage);
        assertFalse(v.screensaverEnabled);
    }

    @Test public void screensaverTypeCyclesBothWaysAndKeepsTheRest() {
        LauncherSettings s = LauncherSettings.DEFAULTS.withVpnPackage("x");
        LauncherSettings video = s.nextScreensaverType();
        assertEquals(LauncherSettings.ScreensaverType.NASA_VIDEO, video.screensaverType);
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, video.nextScreensaverType().screensaverType);
        assertEquals(LauncherSettings.ScreensaverType.NASA_VIDEO, s.prevScreensaverType().screensaverType);
        assertEquals("x", video.vpnPackage);
        assertEquals(2, video.screensaverDelayMin);
    }

    @Test public void storedValuesOutsideTheOptionsFallBackToDefaults() {
        LauncherSettings s = LauncherSettings.fromStored(false, 7, "BOGUS", false, 45, false, null, 66, 95);
        assertFalse(s.screensaverEnabled);
        assertFalse(s.dimEnabled);
        assertEquals(2, s.screensaverDelayMin);
        assertEquals(30, s.dimDelaySec);
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, s.screensaverType);
        assertFalse(s.vpnButtonVisible);
        assertEquals("", s.vpnPackage);
        assertEquals(100, s.uiScalePercent);
        assertEquals(100, s.previewScalePercent);
        LauncherSettings ok = LauncherSettings.fromStored(true, 10, "NASA_VIDEO", true, 120, true, "p.q", 85, 130);
        assertEquals(10, ok.screensaverDelayMin);
        assertEquals(120, ok.dimDelaySec);
        assertEquals(LauncherSettings.ScreensaverType.NASA_VIDEO, ok.screensaverType);
        assertEquals("p.q", ok.vpnPackage);
        assertEquals(85, ok.uiScalePercent);
        assertEquals(130, ok.previewScalePercent);
    }

    @Test public void constructorRejectsUnknownDelays() {
        try {
            new LauncherSettings(true, 4, LauncherSettings.ScreensaverType.STARFIELD, true, 30, true, "", 100, 100);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test public void uiScaleStepsByFiveStopsAtTheEndsAndIsKeptByEveryOtherChange() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertEquals(105, s.nextUiScale().uiScalePercent);
        assertEquals(95, s.prevUiScale().uiScalePercent);
        LauncherSettings max = s;
        for (int i = 0; i < 20; i++) max = max.nextUiScale();
        assertEquals(130, max.uiScalePercent);
        LauncherSettings min = s;
        for (int i = 0; i < 20; i++) min = min.prevUiScale();
        assertEquals(70, min.uiScalePercent);
        assertEquals(70, min.withDimEnabled(false).nextDimDelay().nextScreensaverDelay()
                .nextScreensaverType().withVpnPackage("x").withVpnButtonVisible(false).withScreensaverEnabled(false).uiScalePercent);
        assertEquals("x", min.nextUiScale().withVpnPackage("x").vpnPackage);
        assertTrue(min.nextUiScale().screensaverEnabled);
    }

    @Test public void constructorRejectsAScaleOutsideTheList() {
        try {
            new LauncherSettings(true, 2, LauncherSettings.ScreensaverType.STARFIELD, true, 30, true, "", 101, 100);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test public void previewScaleStepsByTenStopsAtTheEndsAndIsIndependentOfUiScale() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertEquals(110, s.nextPreviewScale().previewScalePercent);
        assertEquals(90, s.prevPreviewScale().previewScalePercent);
        LauncherSettings max = s;
        for (int i = 0; i < 20; i++) max = max.nextPreviewScale();
        assertEquals(150, max.previewScalePercent);
        LauncherSettings min = s;
        for (int i = 0; i < 20; i++) min = min.prevPreviewScale();
        assertEquals(70, min.previewScalePercent);
        assertEquals(70, min.withDimEnabled(false).nextDimDelay().nextUiScale().withVpnPackage("x").previewScalePercent);
        assertEquals(105, min.nextPreviewScale().nextUiScale().uiScalePercent);
        assertEquals(80, min.nextPreviewScale().previewScalePercent);
        assertEquals(135, PreviewScale.cardHeightDp(100));
        assertEquals(150, PreviewScale.rowHeightDp(100));
        assertEquals(203, PreviewScale.cardHeightDp(150));
        assertEquals(95, PreviewScale.cardHeightDp(70));
    }

    @Test public void constructorRejectsAPreviewScaleOutsideTheList() {
        try {
            new LauncherSettings(true, 2, LauncherSettings.ScreensaverType.STARFIELD, true, 30, true, "", 100, 105);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
