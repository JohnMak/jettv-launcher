package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

public class LauncherSettingsTest {
    @Test public void defaults() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertEquals(2, s.screensaverDelayMin);
        assertEquals(30, s.dimDelaySec);
        assertEquals(120_000L, s.screensaverDelayMillis());
        assertEquals(30_000L, s.dimDelayMillis());
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, s.screensaverType);
        assertTrue(s.vpnButtonVisible);
        assertEquals("", s.vpnPackage);
        assertEquals(100, s.uiScalePercent);
        assertEquals(100, s.previewScalePercent);
        assertFalse(s.animationsOff);
        assertEquals(ArtPreset.ALL, s.artPreset);
    }

    @Test public void screensaverDelayCyclesThroughOptionsIncludingOffAndWraps() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertEquals(3, s.nextScreensaverDelay().screensaverDelayMin);
        assertEquals(5, s.nextScreensaverDelay().nextScreensaverDelay().screensaverDelayMin);
        assertEquals(1, s.prevScreensaverDelay().screensaverDelayMin);
        assertEquals(0, s.prevScreensaverDelay().prevScreensaverDelay().screensaverDelayMin);
        LauncherSettings off = s.prevScreensaverDelay().prevScreensaverDelay();
        assertEquals(1, off.nextScreensaverDelay().screensaverDelayMin);
        assertEquals(10, off.prevScreensaverDelay().screensaverDelayMin);
        assertEquals(0, LauncherSettings.SCREENSAVER_DELAYS_MIN[0]);
    }

    @Test public void dimDelayCyclesThroughOptionsIncludingOffAndWraps() {
        LauncherSettings s = LauncherSettings.DEFAULTS;
        assertEquals(60, s.nextDimDelay().dimDelaySec);
        assertEquals(15, s.prevDimDelay().dimDelaySec);
        assertEquals(0, s.prevDimDelay().prevDimDelay().dimDelaySec);
        LauncherSettings off = s.prevDimDelay().prevDimDelay();
        assertEquals(120, off.prevDimDelay().dimDelaySec);
        assertEquals(15, off.nextDimDelay().dimDelaySec);
        assertEquals(0, s.nextDimDelay().nextDimDelay().nextDimDelay().dimDelaySec);
        assertEquals(15, s.nextDimDelay().nextDimDelay().nextDimDelay().nextDimDelay().dimDelaySec);
    }

    @Test public void withersKeepTheRest() {
        LauncherSettings s = LauncherSettings.DEFAULTS.withAnimationsOff(true);
        assertTrue(s.animationsOff);
        assertEquals(2, s.screensaverDelayMin);
        assertEquals(30, s.dimDelaySec);
        LauncherSettings v = s.withVpnButtonVisible(false).withVpnPackage("a.b");
        assertFalse(v.vpnButtonVisible);
        assertEquals("a.b", v.vpnPackage);
        assertTrue(v.animationsOff);
        assertTrue(v.nextUiScale().nextPreviewScale().nextDimDelay().animationsOff);
        assertFalse(v.withAnimationsOff(false).animationsOff);
    }

    @Test public void screensaverTypeCyclesBothWaysThroughThreeTypesAndWrapsAndKeepsTheRest() {
        LauncherSettings s = LauncherSettings.DEFAULTS.withVpnPackage("x");
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, s.screensaverType);
        // forward: STARFIELD -> NASA_VIDEO -> ART -> STARFIELD (wrap)
        LauncherSettings video = s.nextScreensaverType();
        assertEquals(LauncherSettings.ScreensaverType.NASA_VIDEO, video.screensaverType);
        LauncherSettings art = video.nextScreensaverType();
        assertEquals(LauncherSettings.ScreensaverType.ART, art.screensaverType);
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, art.nextScreensaverType().screensaverType);
        // backward: STARFIELD -> ART (wrap) -> NASA_VIDEO -> STARFIELD
        LauncherSettings artBack = s.prevScreensaverType();
        assertEquals(LauncherSettings.ScreensaverType.ART, artBack.screensaverType);
        LauncherSettings videoBack = artBack.prevScreensaverType();
        assertEquals(LauncherSettings.ScreensaverType.NASA_VIDEO, videoBack.screensaverType);
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, videoBack.prevScreensaverType().screensaverType);
        // unrelated fields survive the cycle
        assertEquals("x", video.vpnPackage);
        assertEquals("x", art.vpnPackage);
        assertEquals(2, video.screensaverDelayMin);
        assertEquals(ArtPreset.ALL, art.artPreset);
    }

    @Test public void storedValuesOutsideTheOptionsFallBackToDefaults() {
        LauncherSettings s = LauncherSettings.fromStored(true, 7, "BOGUS", true, 45, false, null, 66, 95, true, "bogus_preset");
        assertEquals(2, s.screensaverDelayMin);
        assertEquals(30, s.dimDelaySec);
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, s.screensaverType);
        assertFalse(s.vpnButtonVisible);
        assertEquals("", s.vpnPackage);
        assertEquals(100, s.uiScalePercent);
        assertEquals(100, s.previewScalePercent);
        assertTrue(s.animationsOff);
        assertEquals(ArtPreset.ALL, s.artPreset);
        LauncherSettings ok = LauncherSettings.fromStored(true, 10, "NASA_VIDEO", true, 120, true, "p.q", 85, 130, false, "era_19c");
        assertEquals(10, ok.screensaverDelayMin);
        assertEquals(120, ok.dimDelaySec);
        assertEquals(LauncherSettings.ScreensaverType.NASA_VIDEO, ok.screensaverType);
        assertEquals("p.q", ok.vpnPackage);
        assertEquals(85, ok.uiScalePercent);
        assertEquals(130, ok.previewScalePercent);
        assertFalse(ok.animationsOff);
        assertEquals(ArtPreset.ERA_19C, ok.artPreset);
        LauncherSettings artType = LauncherSettings.fromStored(true, 5, "ART", true, 60, true, "", 100, 100, false, "avantgarde");
        assertEquals(LauncherSettings.ScreensaverType.ART, artType.screensaverType);
        assertEquals(ArtPreset.AVANTGARDE, artType.artPreset);
        LauncherSettings nullKey = LauncherSettings.fromStored(true, 5, "STARFIELD", true, 60, true, "", 100, 100, false, null);
        assertEquals(ArtPreset.ALL, nullKey.artPreset);
    }

    @Test public void legacyEnabledFlagsMigrateToDelayZero() {
        // enabled flag x stored delay: false -> 0 (even for an invalid delay), true -> normalized delay
        LauncherSettings offOff = LauncherSettings.fromStored(false, 5, "STARFIELD", false, 60, true, "", 100, 100, false, "all");
        assertEquals(0, offOff.screensaverDelayMin);
        assertEquals(0, offOff.dimDelaySec);
        LauncherSettings offBogus = LauncherSettings.fromStored(false, 7, "STARFIELD", false, 45, true, "", 100, 100, false, "all");
        assertEquals(0, offBogus.screensaverDelayMin);
        assertEquals(0, offBogus.dimDelaySec);
        LauncherSettings mixed = LauncherSettings.fromStored(true, 5, "STARFIELD", false, 60, true, "", 100, 100, false, "all");
        assertEquals(5, mixed.screensaverDelayMin);
        assertEquals(0, mixed.dimDelaySec);
        LauncherSettings mixed2 = LauncherSettings.fromStored(false, 5, "STARFIELD", true, 60, true, "", 100, 100, false, "all");
        assertEquals(0, mixed2.screensaverDelayMin);
        assertEquals(60, mixed2.dimDelaySec);
        LauncherSettings onBogus = LauncherSettings.fromStored(true, 7, "STARFIELD", true, 45, true, "", 100, 100, false, "all");
        assertEquals(2, onBogus.screensaverDelayMin);
        assertEquals(30, onBogus.dimDelaySec);
        // new format (no legacy flags => true) keeps an explicit Off
        LauncherSettings newOff = LauncherSettings.fromStored(true, 0, "STARFIELD", true, 0, true, "", 100, 100, false, "all");
        assertEquals(0, newOff.screensaverDelayMin);
        assertEquals(0, newOff.dimDelaySec);
        assertEquals(0L, newOff.screensaverDelayMillis());
    }

    @Test public void artPresetRoundTripsThroughFromStoredForEveryPresetKey() {
        for (ArtPreset p : ArtPreset.values()) {
            LauncherSettings s = LauncherSettings.fromStored(true, 2, "ART", true, 30, true, "", 100, 100, false, p.key);
            assertEquals(p, s.artPreset);
        }
    }

    @Test public void withArtPresetPersistsTheChosenPresetAndKeepsTheRest() {
        LauncherSettings s = LauncherSettings.DEFAULTS.withVpnPackage("x").nextScreensaverType().nextScreensaverType();
        assertEquals(LauncherSettings.ScreensaverType.ART, s.screensaverType);
        assertEquals(ArtPreset.ALL, s.artPreset);
        LauncherSettings withPreset = s.withArtPreset(ArtPreset.PEREDVIZHNIKI);
        assertEquals(ArtPreset.PEREDVIZHNIKI, withPreset.artPreset);
        assertEquals(LauncherSettings.ScreensaverType.ART, withPreset.screensaverType);
        assertEquals("x", withPreset.vpnPackage);
        // an unrelated-setting change and a type cycle both keep the chosen preset untouched
        LauncherSettings afterUnrelated = withPreset.withAnimationsOff(true).nextDimDelay();
        assertEquals(ArtPreset.PEREDVIZHNIKI, afterUnrelated.artPreset);
        LauncherSettings afterTypeCycle = withPreset.nextScreensaverType().prevScreensaverType();
        assertEquals(ArtPreset.PEREDVIZHNIKI, afterTypeCycle.artPreset);
    }

    @Test public void constructorRejectsANullArtPreset() {
        try {
            new LauncherSettings(2, LauncherSettings.ScreensaverType.STARFIELD, 30, true, "", 100, 100, false, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test public void constructorRejectsUnknownDelays() {
        try {
            new LauncherSettings(4, LauncherSettings.ScreensaverType.STARFIELD, 30, true, "", 100, 100, false, ArtPreset.ALL);
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
        assertEquals(70, min.nextDimDelay().nextScreensaverDelay()
                .nextScreensaverType().withVpnPackage("x").withVpnButtonVisible(false).withAnimationsOff(true).uiScalePercent);
        assertEquals("x", min.nextUiScale().withVpnPackage("x").vpnPackage);
        assertEquals(2, min.nextUiScale().screensaverDelayMin);
    }

    @Test public void constructorRejectsAScaleOutsideTheList() {
        try {
            new LauncherSettings(2, LauncherSettings.ScreensaverType.STARFIELD, 30, true, "", 101, 100, false, ArtPreset.ALL);
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
        assertEquals(70, min.nextDimDelay().nextUiScale().withVpnPackage("x").previewScalePercent);
        assertEquals(105, min.nextPreviewScale().nextUiScale().uiScalePercent);
        assertEquals(80, min.nextPreviewScale().previewScalePercent);
        assertEquals(135, PreviewScale.cardHeightDp(100));
        assertEquals(150, PreviewScale.rowHeightDp(100));
        assertEquals(203, PreviewScale.cardHeightDp(150));
        assertEquals(95, PreviewScale.cardHeightDp(70));
    }

    @Test public void constructorRejectsAPreviewScaleOutsideTheList() {
        try {
            new LauncherSettings(2, LauncherSettings.ScreensaverType.STARFIELD, 30, true, "", 100, 105, false, ArtPreset.ALL);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
