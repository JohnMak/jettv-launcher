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
        assertFalse(b.contains("com.xgimi"));
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

    @Test public void staticDenyNamesAndPrefixesAreProtected() {
        ProtectedPackages b = ProtectedPackages.base();
        for (String p : new String[] {"com.google.android.gsf", "com.google.android.webview",
                "com.google.android.trichromelibrary", "com.android.webview", "com.google.android.ext.services",
                "android.ext.services", "com.android.inputdevices", "com.android.externalstorage",
                "com.android.location.fused", "com.android.networkstack", "com.android.networkstack.tethering",
                "com.google.android.networkstack", "com.google.android.networkstack.permissionconfig",
                "com.android.wifi", "com.android.wifi.resources", "com.android.se", "com.android.nfc",
                "com.android.server.telecom", "com.android.keychain", "com.android.certinstaller",
                "com.android.dynsystem", "com.android.tv.frameworkpackagestubs",
                "com.droidlogic.tv", "com.amlogic.x", "com.mediatek.y", "com.mstar.z", "com.xgimi.gimiplayer"}) {
            assertEquals(p, ProtectedPackages.Level.FULL, b.level(p));
            assertTrue(p, b.contains(p));
        }
        assertEquals(ProtectedPackages.Level.NONE, b.level("com.android.sex"));
        assertEquals(ProtectedPackages.Level.NONE, b.level("com.droidlogicx.a"));
    }

    @Test public void profileListedVendorPackagesStayAllowedButStaticNamesDoNot() {
        ProtectedPackages p = ProtectedPackages.base().allowingVendor(Arrays.asList("com.xgimi.gimiplayer", "com.android.nfc"));
        assertEquals(ProtectedPackages.Level.NONE, p.level("com.xgimi.gimiplayer"));
        assertEquals(ProtectedPackages.Level.FULL, p.level("com.xgimi.other"));
        assertEquals(ProtectedPackages.Level.FULL, p.level("com.android.nfc"));
    }

    @Test public void disableOnlyLevelIsWeakerThanFull() {
        ProtectedPackages p = ProtectedPackages.base().withDisableOnly(Arrays.asList("h.ome", "com.google.android.gms"));
        assertEquals(ProtectedPackages.Level.DISABLE_ONLY, p.level("h.ome"));
        assertFalse(p.contains("h.ome"));
        assertEquals(ProtectedPackages.Level.FULL, p.level("com.google.android.gms"));
    }
}
