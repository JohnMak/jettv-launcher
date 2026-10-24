package com.johnmak.atvlauncher;

import static org.junit.Assert.assertNotEquals;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Regression: an app update that keeps the same label must still change {@link AppLoader.Result#signature},
 * so {@code applyApps()} rebuilds the adapter and the tile picks up the new AppEntry/icon/version instead of
 * showing a stale glow.
 */
public class AppRefreshSignatureTest {

    private static String signatureOf(long versionCode) {
        // AppEntry is a pure value holder; the Android-typed fields are unused by the signature.
        AppEntry entry = new AppEntry("com.example.app", "Example", null, null, null, versionCode);
        List<AppEntry> apps = Collections.singletonList(entry);
        return new AppLoader.Result(apps).signature;
    }

    @Test
    public void signatureChangesWhenOnlyVersionCodeChanges() {
        assertNotEquals(signatureOf(1L), signatureOf(2L));
    }
}
