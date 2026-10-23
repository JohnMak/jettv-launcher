package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PmOutputParserTest {
    @Test
    public void successNeedsExitZeroAndMarker() {
        assertEquals("Success", PmOutputParser.parse(ShellCommand.Verb.PM_UNINSTALL, 0, "Success\n", "").value());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.PM_UNINSTALL_UPDATES, 0, "Success", "").isOk());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.PM_REMOVE_FOR_USER, 0, "Success", "").isOk());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.PM_RESTORE, 0, "Package com.a.b installed for user: 0", "").isOk());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.PM_DISABLE, 0, "Package com.a.b new state: disabled-user", "").isOk());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.PM_ENABLE, 0, "Package com.a.b new state: enabled", "").isOk());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.PM_GRANT, 0, "", "").isOk());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.APPOPS_ALLOW_USAGE_STATS, 0, "\n", "").isOk());
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.ROLE_SET_HOME, 0, null, "").isOk());
    }

    @Test
    public void failuresAreCommandFailedWithOutput() {
        FetchResult<String> r = PmOutputParser.parse(ShellCommand.Verb.PM_UNINSTALL, 1, "Failure [DELETE_FAILED_INTERNAL_ERROR]", "");
        assertFalse(r.isOk());
        assertEquals(ShellError.COMMAND_FAILED, ShellError.of(r));
        assertTrue(r.error().contains("DELETE_FAILED_INTERNAL_ERROR"));
        // exit 0 but no marker
        assertEquals(ShellError.COMMAND_FAILED,
                ShellError.of(PmOutputParser.parse(ShellCommand.Verb.PM_UNINSTALL, 0, "Failure [x]", "")));
        // marker but non-zero exit
        assertFalse(PmOutputParser.parse(ShellCommand.Verb.PM_UNINSTALL, 1, "Success", "").isOk());
        // empty-output verbs fail on any text
        assertFalse(PmOutputParser.parse(ShellCommand.Verb.PM_GRANT, 0, "Exception occurred", "").isOk());
        // "not found" in pm output is a command failure, never a missing tool
        assertEquals(ShellError.COMMAND_FAILED,
                ShellError.of(PmOutputParser.parse(ShellCommand.Verb.PM_DISABLE, 1, "Unknown package: com.a.b not found", "")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void gmpfVerbsAreNotParsedHere() {
        PmOutputParser.parse(ShellCommand.Verb.GET_BRIGHTNESS, 0, "", "");
    }

    @Test
    public void emptyMarkerVerbsIgnoreStderrWarningsButNotOtherStderr() {
        ShellCommand.Verb v = ShellCommand.Verb.PM_GRANT;
        assertTrue(PmOutputParser.parse(v, 0, "", "WARNING: linker: unused DT entry\nWARNING: x\n").isOk());
        assertTrue(PmOutputParser.parse(v, 0, "", null).isOk());
        assertFalse(PmOutputParser.parse(v, 0, "", "WARNING: a\nException occurred").isOk());
        assertFalse(PmOutputParser.parse(v, 0, "text", "").isOk());
        assertFalse(PmOutputParser.parse(v, 1, "", "WARNING: a").isOk());
        // marker verbs are unaffected by warnings
        assertTrue(PmOutputParser.parse(ShellCommand.Verb.PM_UNINSTALL, 0, "Success", "WARNING: a").isOk());
    }
}
