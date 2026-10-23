package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackdropKeysTest {
    @Test
    public void appKeyRoundTripsAndPostersAreNotApps() {
        String key = BackdropKeys.app("com.example.tv");
        assertTrue(BackdropKeys.isApp(key));
        assertEquals("com.example.tv", BackdropKeys.packageOf(key));
        assertFalse(BackdropKeys.isApp("https://img.example/poster.jpg"));
        assertFalse(BackdropKeys.isApp("content://provider/poster/1"));
    }
}
