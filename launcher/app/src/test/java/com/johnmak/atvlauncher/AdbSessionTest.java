package com.johnmak.atvlauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.TreeSet;

import org.junit.Test;

public class AdbSessionTest {
    @Test public void sessionOffersOnlyTypedOperations() throws Exception {
        TreeSet<String> ops = new TreeSet<>();
        for (Method m : AdbSession.class.getDeclaredMethods()) {
            ops.add(m.getName() + Arrays.toString(m.getParameterTypes()));
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(m.getName() + " takes a raw " + p, p == String.class || p == String[].class || p == java.io.File.class);
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList(
                "disconnect[]",
                "exec[class com.johnmak.atvlauncher.ShellCommand]",
                "install[class com.johnmak.atvlauncher.VerifiedApk]")), ops);
    }

    @Test public void onlyTheVerifierCreatesVerifiedApks() {
        // package-private constructor: no public way to forge one outside the package
        assertArrayEquals(new Object[0], java.util.Arrays.stream(VerifiedApk.class.getConstructors()).toArray());
    }

    @Test public void shellErrorsMapToUpdateErrors() {
        assertEquals(UpdateError.ADB_UNAUTHORIZED, UpdateError.fromShell(ShellError.UNAUTHORIZED));
        assertEquals(UpdateError.ADB_UNREACHABLE, UpdateError.fromShell(ShellError.UNREACHABLE));
        assertEquals(UpdateError.INSTALL_FAILED, UpdateError.fromShell(ShellError.COMMAND_FAILED));
        assertEquals(UpdateError.INSTALL_FAILED, UpdateError.fromShell(ShellError.TOOL_MISSING));
    }
}
