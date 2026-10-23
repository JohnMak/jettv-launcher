package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.Test;

import dadb.AdbAuthException;
import dadb.AdbConnectException;
import dadb.AdbConnectionClosedException;

public class ShellErrorClassifierTest {
    @Test
    public void exceptionsMapToKinds() {
        assertEquals(ShellError.UNAUTHORIZED,
                ShellErrorClassifier.classify(new AdbAuthException("Device rejected authentication", null)));
        assertEquals(ShellError.UNREACHABLE, ShellErrorClassifier.classify(new ConnectException("refused")));
        assertEquals(ShellError.UNREACHABLE, ShellErrorClassifier.classify(
                new AdbConnectException("Connection handshake failed", new ConnectException("refused"))));
        assertEquals(ShellError.UNREACHABLE,
                ShellErrorClassifier.classify(new SocketTimeoutException("connect timed out")));
        assertEquals(ShellError.UNREACHABLE,
                ShellErrorClassifier.classify(new AdbConnectionClosedException("closed", null)));
    }

    @Test
    public void readTimeoutDuringHandshakeMeansPendingDialog() {
        Throwable pending = new AdbConnectException("Connection handshake failed",
                new SocketTimeoutException("Read timed out"));
        assertEquals(ShellError.UNAUTHORIZED, ShellErrorClassifier.classify(pending));
        assertEquals(ShellError.UNREACHABLE,
                ShellErrorClassifier.classify(new SocketTimeoutException("Read timed out")));
    }

    @Test
    public void outputMapsToKinds() {
        assertNull(ShellErrorClassifier.classifyOutput(0, "return_data : {5 }"));
        assertNull(ShellErrorClassifier.classifyOutput(0, ""));
        assertEquals(ShellError.COMMAND_FAILED, ShellErrorClassifier.classifyOutput(1, "boom"));
        assertEquals(ShellError.TOOL_MISSING, ShellErrorClassifier.classifyOutput(127, "gmpfUnit: inaccessible or not found"));
        assertEquals(ShellError.TOOL_MISSING, ShellErrorClassifier.classifyOutput(127, "boom"));
        assertEquals(ShellError.TOOL_MISSING, ShellErrorClassifier.classifyOutput(1, "sh: gmpfUnit: Not Found"));
        assertNull(ShellErrorClassifier.classifyOutput(0, "sh: gmpfUnit: Not Found"));
    }

    @Test
    public void kindTravelsInsideFetchResult() {
        FetchResult<String> failed = ShellError.fail(ShellError.UNAUTHORIZED, "pending");
        assertFalse(failed.isOk());
        assertSame(ShellError.UNAUTHORIZED, ShellError.of(failed));
        assertTrue(failed.error().contains("pending"));
        assertNull(ShellError.of(FetchResult.ok("x")));
        assertNull(ShellError.of(FetchResult.<String>err("plain")));
        FetchResult<Integer> moved = failed.propagate();
        assertEquals(failed.error(), moved.error());
        assertSame(ShellError.UNAUTHORIZED, ShellError.of(moved));
    }
}
