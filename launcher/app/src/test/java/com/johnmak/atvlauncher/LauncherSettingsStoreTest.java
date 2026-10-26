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

    @Test public void freshPrefsLoadDefaultsWithArtPresetAll() {
        LauncherSettings s = storeOver(new FakeSharedPreferences()).load();
        assertEquals(ArtPreset.ALL, s.artPreset);
        assertEquals(LauncherSettings.ScreensaverType.STARFIELD, s.screensaverType);
        assertEquals(2, s.screensaverDelayMin);
        assertEquals(30, s.dimDelaySec);
    }

    @Test public void aSavedNonDefaultPresetSurvivesSaveLoadRoundTripAndRestart() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        LauncherSettings toSave = LauncherSettings.DEFAULTS
                .nextScreensaverType().nextScreensaverType()   // -> ART
                .withArtPreset(ArtPreset.ERA_19C);
        assertEquals(LauncherSettings.ScreensaverType.ART, toSave.screensaverType);
        storeOver(prefs).save(toSave);

        // Fresh store over the same prefs instance == a process restart.
        LauncherSettings reloaded = storeOver(prefs).load();
        assertEquals(LauncherSettings.ScreensaverType.ART, reloaded.screensaverType);
        assertEquals(ArtPreset.ERA_19C, reloaded.artPreset);
    }

    @Test public void aChosenPresetSurvivesATypeCycleAndAnUnrelatedSettingChange() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        storeOver(prefs).save(LauncherSettings.DEFAULTS
                .nextScreensaverType().nextScreensaverType()   // -> ART
                .withArtPreset(ArtPreset.AVANTGARDE));

        // Reload, cycle the type away and back, flip an unrelated setting, persist, restart, reload.
        LauncherSettings loaded = storeOver(prefs).load();
        LauncherSettings mutated = loaded
                .nextScreensaverType().prevScreensaverType()   // type cycle round trip
                .withAnimationsOff(true).nextDimDelay();
        storeOver(prefs).save(mutated);

        LauncherSettings afterRestart = storeOver(prefs).load();
        assertEquals(ArtPreset.AVANTGARDE, afterRestart.artPreset);
        assertEquals(LauncherSettings.ScreensaverType.ART, afterRestart.screensaverType);
        assertTrue(afterRestart.animationsOff);
    }

    @Test public void everyPresetRoundTripsThroughTheRealPrefsBoundary() {
        for (ArtPreset p : ArtPreset.values()) {
            FakeSharedPreferences prefs = new FakeSharedPreferences();
            storeOver(prefs).save(LauncherSettings.DEFAULTS
                    .nextScreensaverType().nextScreensaverType()
                    .withArtPreset(p));
            assertEquals(p, storeOver(prefs).load().artPreset);
        }
    }

    @Test public void anInvalidStoredPresetKeyLoadsAsAll() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit()
                .putString(LauncherSettingsStore.KEY_SCREENSAVER_TYPE,
                        LauncherSettings.ScreensaverType.ART.name())
                .putString(LauncherSettingsStore.KEY_ART_PRESET, "no_such_preset")
                .apply();
        LauncherSettings s = storeOver(prefs).load();
        assertEquals(LauncherSettings.ScreensaverType.ART, s.screensaverType);
        assertEquals(ArtPreset.ALL, s.artPreset);
    }

    @Test public void aStoredPresetIsPersistedVerbatimWithNoSilentRewrite() {
        // The store never reconciles availability: whatever preset is in the settings is written verbatim and
        // read back verbatim, independent of any catalog (reconciliation is the resolver's job at render time).
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        storeOver(prefs).save(LauncherSettings.DEFAULTS
                .nextScreensaverType().nextScreensaverType()
                .withArtPreset(ArtPreset.PEREDVIZHNIKI));

        // Raw persisted value is the stable key, untouched.
        assertEquals(ArtPreset.PEREDVIZHNIKI.key,
                prefs.getString(LauncherSettingsStore.KEY_ART_PRESET, null));

        // A load+save cycle does not rewrite it to something else.
        storeOver(prefs).save(storeOver(prefs).load());
        assertEquals(ArtPreset.PEREDVIZHNIKI.key,
                prefs.getString(LauncherSettingsStore.KEY_ART_PRESET, null));
        assertEquals(ArtPreset.PEREDVIZHNIKI, storeOver(prefs).load().artPreset);
    }
}
