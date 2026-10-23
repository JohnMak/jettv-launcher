package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

public class ApkChecksTest {
    private static final String D = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void shaLineTakesTheFirstToken() {
        assertEquals(D, ApkChecks.parseSha256(D + "  jettv-launcher-1.2.3.apk\n").value());
        assertEquals(D, ApkChecks.parseSha256(D).value());
        assertEquals(D, ApkChecks.parseSha256("\n" + D.toUpperCase() + " *x.apk\nignored second line").value());
    }

    @Test public void shaLineRejectsGarbage() {
        assertFalse(ApkChecks.parseSha256("").isOk());
        assertFalse(ApkChecks.parseSha256("   \n").isOk());
        assertFalse(ApkChecks.parseSha256(D.substring(1)).isOk());
        assertFalse(ApkChecks.parseSha256(D + "0").isOk());
        assertFalse(ApkChecks.parseSha256("zz" + D.substring(2)).isOk());
        assertFalse(ApkChecks.parseSha256(null).isOk());
    }

    @Test public void hexIsLowercaseAndPadded() {
        assertEquals("000fff10", ApkChecks.hex(new byte[]{0, 15, (byte) 255, 16}));
    }

    @Test public void signersMustBeTheSameNonEmptySet() {
        assertTrue(ApkChecks.sameSigners(set("a", "b"), set("b", "a")));
        assertFalse(ApkChecks.sameSigners(set("a"), set("a", "b")));
        assertFalse(ApkChecks.sameSigners(set("a"), set("b")));
        assertFalse(ApkChecks.sameSigners(set(), set()));
        assertFalse(ApkChecks.sameSigners(set("a"), set()));
    }

    private static java.util.Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }
}
