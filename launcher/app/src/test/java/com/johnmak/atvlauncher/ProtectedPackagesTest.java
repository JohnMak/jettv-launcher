package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class ProtectedPackagesTest {
    @Test public void baseCoversCoreSystemAndPrefixRule() {
        ProtectedPackages b = ProtectedPackages.base();
        for (String p : new String[] {"com.johnmak.atvlauncher", "android", "com.android.systemui", "com.android.shell",
                "com.android.bluetooth", "com.android.tv.settings", "com.android.settings", "com.android.packageinstaller",
                "com.google.android.packageinstaller", "com.android.permissioncontroller",
                "com.google.android.permissioncontroller", "com.google.android.gms", "com.android.vending",
                "com.google.android.tv.remote.service", "com.android.providers.media", "com.android.providers.settings"}) {
            assertTrue(p, b.contains(p));
        }
        assertFalse(b.contains("com.android.providers"));
        assertFalse(b.contains("com.android.providersx.foo"));
        assertFalse(b.contains("com.xgimi.gimiplayer"));
        assertFalse(b.contains(null));
    }

    @Test public void dynamicAdditionsAreImmutableCopies() {
        ProtectedPackages b = ProtectedPackages.base();
        ProtectedPackages d = b.with(Arrays.asList("com.ime.keyboard", "com.other.home"));
        assertTrue(d.contains("com.ime.keyboard"));
        assertTrue(d.contains("com.other.home"));
        assertTrue(d.contains("android"));
        assertFalse(b.contains("com.ime.keyboard"));
    }
}
