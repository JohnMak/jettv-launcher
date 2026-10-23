package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SemVerTest {
    private static SemVer v(String s) { return SemVer.parse(s).value(); }

    @Test public void parsesStrictTriples() {
        SemVer v = v("1.20.3");
        assertEquals(1, v.major);
        assertEquals(20, v.minor);
        assertEquals(3, v.patch);
        assertEquals("1.20.3", v.toString());
    }

    @Test public void rejectsEverythingElse() {
        for (String bad : new String[]{"", "1.2", "1.2.3.4", "v1.2.3", "1.2.3-dev", "01.2.3", "1.2.03", "1.2.x", " 1.2.3",
                "1.1000.0", "1.0.1000", "2001.0.0", "-1.0.0"}) {
            assertFalse(bad, SemVer.parse(bad).isOk());
        }
        assertFalse(SemVer.parse(null).isOk());
    }

    @Test public void ordersNumericallyNotLexically() {
        assertTrue(v("1.10.0").compareTo(v("1.9.9")) > 0);
        assertTrue(v("2.0.0").compareTo(v("1.999.999")) > 0);
        assertTrue(v("1.2.4").compareTo(v("1.2.3")) > 0);
        assertEquals(0, v("1.2.3").compareTo(v("1.2.3")));
        assertEquals(v("1.2.3"), v("1.2.3"));
        assertEquals(v("1.2.3").hashCode(), v("1.2.3").hashCode());
    }

    @Test public void versionNameKeepsTheReleasePartOfDevBuilds() {
        assertEquals("0.0.0", SemVer.ofVersionName("0.0.0-dev.3+gabc123.dirty").value().toString());
        assertEquals("1.2.3", SemVer.ofVersionName("1.2.3").value().toString());
        assertFalse(SemVer.ofVersionName("garbage-dev.1").isOk());
    }
}
