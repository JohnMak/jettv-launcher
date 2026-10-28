package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Exercises the real {@link LauncherSettingsStore#load()}/{@link LauncherSettingsStore#save} persistence boundary
 * through an injected in-memory {@link FakeSharedPreferences}. The fake instance models the on-disk prefs file, so
 * constructing a fresh store over the same instance is a faithful "process restart".
 */
public class LauncherSettingsStoreTest {

    private static LauncherSettingsStore storeOver(FakeSharedPreferences prefs) {
        return new LauncherSettingsStore(prefs);
    }

    @Test public void freshPrefsLoadDefaultsWithArtPresetDefault() {
        LauncherSettings s = storeOver(new FakeSharedPreferences()).load();
        assertEquals(WallhavenCategory.DEFAULT, s.artPreset);
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, s.screensaverType);
        assertEquals(2, s.screensaverDelayMin);
        assertEquals(30, s.dimDelaySec);
    }

    @Test public void aSavedNonDefaultPresetSurvivesSaveLoadRoundTripAndRestart() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        LauncherSettings toSave = LauncherSettings.DEFAULTS
                .nextScreensaverType().nextScreensaverType()   // -> ART
                .withArtPreset(WallhavenCategory.LANDSCAPES);
        assertEquals(LauncherSettings.ScreensaverType.ART, toSave.screensaverType);
        storeOver(prefs).save(toSave);

        // Fresh store over the same prefs instance == a process restart.
        LauncherSettings reloaded = storeOver(prefs).load();
        assertEquals(LauncherSettings.ScreensaverType.ART, reloaded.screensaverType);
        assertEquals(WallhavenCategory.LANDSCAPES, reloaded.artPreset);
    }

    @Test public void aChosenPresetSurvivesATypeCycleAndAnUnrelatedSettingChange() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        storeOver(prefs).save(LauncherSettings.DEFAULTS
                .nextScreensaverType().nextScreensaverType()   // -> ART
                .withArtPreset(WallhavenCategory.ABSTRACT));

        // Reload, cycle the type away and back, flip an unrelated setting, persist, restart, reload.
        LauncherSettings loaded = storeOver(prefs).load();
        LauncherSettings mutated = loaded
                .nextScreensaverType().prevScreensaverType()   // type cycle round trip
                .withAnimationsOff(true).nextDimDelay();
        storeOver(prefs).save(mutated);

        LauncherSettings afterRestart = storeOver(prefs).load();
        assertEquals(WallhavenCategory.ABSTRACT, afterRestart.artPreset);
        assertEquals(LauncherSettings.ScreensaverType.ART, afterRestart.screensaverType);
        assertTrue(afterRestart.animationsOff);
    }

    @Test public void everyCategoryRoundTripsThroughTheRealPrefsBoundary() {
        for (WallhavenCategory c : WallhavenCategory.values()) {
            FakeSharedPreferences prefs = new FakeSharedPreferences();
            storeOver(prefs).save(LauncherSettings.DEFAULTS
                    .nextScreensaverType().nextScreensaverType()
                    .withArtPreset(c));
            assertEquals(c, storeOver(prefs).load().artPreset);
        }
    }

    @Test public void anInvalidStoredPresetKeyLoadsAsDefault() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit()
                .putString(LauncherSettingsStore.KEY_SCREENSAVER_TYPE,
                        LauncherSettings.ScreensaverType.ART.name())
                .putString(LauncherSettingsStore.KEY_ART_PRESET, "no_such_preset")
                .apply();
        LauncherSettings s = storeOver(prefs).load();
        assertEquals(LauncherSettings.ScreensaverType.ART, s.screensaverType);
        assertEquals(WallhavenCategory.DEFAULT, s.artPreset);
    }

    @Test public void aLegacyArtPresetKeyLoadsAsDefaultWithoutCrashing() {
        // A key persisted by the old (pre-cutover) ArtPreset scheme must resolve gracefully on the new type —
        // plan B2's legacy-key fallback, now living on WallhavenCategory instead of ArtPreset.
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit()
                .putString(LauncherSettingsStore.KEY_SCREENSAVER_TYPE,
                        LauncherSettings.ScreensaverType.ART.name())
                .putString(LauncherSettingsStore.KEY_ART_PRESET, "peredvizhniki")
                .apply();
        LauncherSettings s = storeOver(prefs).load();
        assertEquals(WallhavenCategory.DEFAULT, s.artPreset);
    }

    @Test public void aStoredPresetIsPersistedVerbatimWithNoSilentRewrite() {
        // The store never reconciles availability: whatever category is in the settings is written verbatim and
        // read back verbatim.
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        storeOver(prefs).save(LauncherSettings.DEFAULTS
                .nextScreensaverType().nextScreensaverType()
                .withArtPreset(WallhavenCategory.MINIMAL));

        // Raw persisted value is the stable key, untouched.
        assertEquals(WallhavenCategory.MINIMAL.key,
                prefs.getString(LauncherSettingsStore.KEY_ART_PRESET, null));

        // A load+save cycle does not rewrite it to something else.
        storeOver(prefs).save(storeOver(prefs).load());
        assertEquals(WallhavenCategory.MINIMAL.key,
                prefs.getString(LauncherSettingsStore.KEY_ART_PRESET, null));
        assertEquals(WallhavenCategory.MINIMAL, storeOver(prefs).load().artPreset);
    }
}
