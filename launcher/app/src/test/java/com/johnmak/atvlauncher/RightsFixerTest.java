package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class RightsFixerTest {
    private static final PackageName OWN = PackageName.parse("com.johnmak.atvlauncher").value();

    /** Applies each command to the probe; optionally fails every command with a kind. */
    static final class FakeSession implements AdbSession {
        final List<String> lines = new ArrayList<>();
        final RequiredRightsTest.FakeProbe probe;
        ShellError failWith;

        FakeSession(RequiredRightsTest.FakeProbe probe) { this.probe = probe; }

        @Override public FetchResult<String> exec(ShellCommand c) {
            lines.add(c.shellLine());
            if (failWith != null) return ShellError.fail(failWith, "boom");
            switch (c.verb()) {
                case PM_GRANT:
                    for (ShellCommand.GrantablePermission p : ShellCommand.GrantablePermission.values()) {
                        if (c.shellLine().endsWith("." + p.name())) probe.granted.add(p);
                    }
                    break;
                case APPOPS_ALLOW_USAGE_STATS: probe.usage = true; break;
                case ROLE_SET_HOME: probe.home = true; break;
                default: throw new AssertionError(c);
            }
            return FetchResult.ok("");
        }

        @Override public FetchResult<String> install(VerifiedApk apk) { throw new AssertionError(); }

        @Override public void disconnect() {}
    }

    private RightsFixer.Outcome run(boolean grant, FakeSession s, RequiredRightsTest.FakeProbe p) {
        RightsFixer.Outcome[] out = new RightsFixer.Outcome[1];
        RightsFixer f = new RightsFixer(s, OWN, p, Runnable::run, Runnable::run);
        if (grant) f.grantMissing(o -> out[0] = o); else f.setHome(o -> out[0] = o);
        assertNotNull(out[0]);
        return out[0];
    }

    @Test public void grantRunsOneCommandPerMissingRightExceptHomeThenReprobes() {
        RequiredRightsTest.FakeProbe p = new RequiredRightsTest.FakeProbe();
        p.granted.add(ShellCommand.GrantablePermission.READ_TV_LISTINGS);
        FakeSession s = new FakeSession(p);
        RightsFixer.Outcome o = run(true, s, p);
        assertEquals(Arrays.asList(
                "pm grant com.johnmak.atvlauncher android.permission.ACCESS_FINE_LOCATION",
                "pm grant com.johnmak.atvlauncher android.permission.WRITE_SECURE_SETTINGS",
                "appops set com.johnmak.atvlauncher GET_USAGE_STATS allow"), s.lines);
        assertEquals(Arrays.asList(RequiredRights.Right.HOME), o.stillMissing);
        assertTrue(o.errors.isEmpty());
        assertEquals(null, o.firstError());
    }

    @Test public void unauthorizedIsReportedAndRightsStayMissing() {
        RequiredRightsTest.FakeProbe p = new RequiredRightsTest.FakeProbe();
        FakeSession s = new FakeSession(p);
        s.failWith = ShellError.UNAUTHORIZED;
        RightsFixer.Outcome o = run(true, s, p);
        assertEquals(4, o.errors.size());
        assertEquals(ShellError.UNAUTHORIZED, o.firstError());
        assertEquals(5, o.stillMissing.size());
    }

    @Test public void setHomeRunsOnlyTheRoleCommand() {
        RequiredRightsTest.FakeProbe p = new RequiredRightsTest.FakeProbe();
        FakeSession s = new FakeSession(p);
        RightsFixer.Outcome o = run(false, s, p);
        assertEquals(Arrays.asList(
                "cmd role add-role-holder --user 0 android.app.role.HOME com.johnmak.atvlauncher"), s.lines);
        assertTrue(!o.stillMissing.contains(RequiredRights.Right.HOME));
    }
}
