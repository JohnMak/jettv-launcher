package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PackageNameTest {
    @Test
    public void validNamesParse() {
        for (String ok : new String[] {"com.johnmak.atvlauncher", "a.b", "com.google.android.youtube.tv", "A.b_1.C2"}) {
            assertTrue(ok, PackageName.parse(ok).isOk());
            assertEquals(ok, PackageName.parse(ok).value().value());
        }
        assertEquals(PackageName.parse("a.b").value(), PackageName.parse("a.b").value());
    }

    @Test
    public void invalidNamesAreRejected() {
        String longName = "a." + "b".repeat(254);
        for (String bad : new String[] {"", "single", ".a.b", "a..b", "a.b.", "1a.b", "a.1b", "a.b c", "a.b;rm -rf /",
                "a.b\n", "a.b$(x)", "a-b.c", longName}) {
            assertFalse("[" + bad + "]", PackageName.parse(bad).isOk());
        }
        assertFalse(PackageName.parse(null).isOk());
        assertTrue(PackageName.parse("a." + "b".repeat(253)).isOk());
    }
}
