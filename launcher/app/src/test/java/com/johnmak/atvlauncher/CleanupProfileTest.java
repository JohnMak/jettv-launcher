package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class CleanupProfileTest {
    private static String asset(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("../../tools/debloat/" + name)), StandardCharsets.UTF_8);
    }

    @Test public void realProfilesParseAndMatchTheirDevices() throws Exception {
        CleanupProfile p = CleanupProfile.parse("xgimi", asset("xgimi-halo-plus.txt")).value();
        assertTrue(p.matches("XK03H", "other"));
        assertTrue(p.matches("other", "aiolia"));
        assertFalse(p.matches("MiTV-AYFR0", "soul"));
        assertTrue(p.entries().size() > 10);
        for (CleanupProfile.Entry e : p.entries()) {
            if (e.pkg.equals("com.google.android.tts") || e.pkg.equals("com.google.android.marvin.talkback")
                    || e.pkg.equals("com.google.android.backdrop")) {
                assertEquals(e.pkg, CleanupProfile.Action.REMOVE, e.action);
                assertTrue(e.pkg, e.optional);
            }
            if (e.pkg.equals("com.xgimi.gimiplayer")) {
                assertEquals(CleanupProfile.Action.DISABLE, e.action);
                assertTrue(e.optional);
            }
        }
        CleanupProfile q = CleanupProfile.parse("mi", asset("xiaomi-tv-box-s2.txt")).value();
        assertTrue(q.matches("MiTV-AYFR0", "x"));
        assertTrue(q.matches("x", "soul"));
        assertFalse(q.matches("XK03H", "aiolia"));
    }

    @Test public void entriesCarryActionOptionalAndRequiresHome() {
        String text = "@match model=M1\n@match product=P1\n"
                + "# comment\n\n"
                + "disable a.b  # c\n"
                + "remove b.c\n"
                + "reset c.d\n"
                + "d.e\n"
                + "disable? e.f requires=home\n"
                + "remove? f.g\n";
        CleanupProfile p = CleanupProfile.parse("t", text).value();
        assertEquals("t", p.name());
        assertEquals(6, p.entries().size());
        CleanupProfile.Entry e0 = p.entries().get(0);
        assertEquals(CleanupProfile.Action.DISABLE, e0.action);
        assertEquals("a.b", e0.pkg);
        assertFalse(e0.optional);
        assertEquals(CleanupProfile.Action.REMOVE, p.entries().get(1).action);
        assertEquals(CleanupProfile.Action.RESET, p.entries().get(2).action);
        assertEquals(CleanupProfile.Action.DISABLE, p.entries().get(3).action);
        assertEquals("d.e", p.entries().get(3).pkg);
        CleanupProfile.Entry e4 = p.entries().get(4);
        assertTrue(e4.optional);
        assertTrue(e4.requiresHome);
        assertTrue(p.entries().get(5).optional);
        assertFalse(p.entries().get(5).requiresHome);
        assertTrue(p.matches("M1", "zzz"));
        assertTrue(p.matches("zzz", "P1"));
        assertFalse(p.matches("zzz", "yyy"));
        assertFalse(p.matches(null, null));
    }

    private static void assertRejected(String text, String fragment) {
        FetchResult<CleanupProfile> r = CleanupProfile.parse("t", text);
        assertFalse(text, r.isOk());
        assertTrue(r.error(), r.error().contains(fragment));
    }

    @Test public void badLinesRejectTheWholeProfileWithLineNumber() {
        assertRejected("@match model=M\nremove a.b\nremove bad pkg\n", "line 3");
        assertRejected("@match model=M\nremove a b.c;rm\n", "line 2");
        assertRejected("@match model=M\nremove nodots\n", "line 2");
        assertRejected("@match model=M\nwipe a.b\n", "line 2");
        assertRejected("@match model=M\nremove a.b requires=nothing\n", "line 2");
        assertRejected("@match model=M\nremove\n", "line 2");
        assertRejected("@match model=\nremove a.b\n", "line 1");
        assertRejected("@match color=red\nremove a.b\n", "line 1");
        assertRejected("@match model=M\nremove a.b\nremove a.b\n", "line 3");
        assertRejected("remove a.b\n", "@match");
        assertRejected(null, "null");
    }
}
