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

public class LampErrorClassifierTest {
    @Test
    public void exceptionsMapToKinds() {
        assertEquals(LampError.UNAUTHORIZED,
                LampErrorClassifier.classify(new AdbAuthException("Device rejected authentication", null)));
        assertEquals(LampError.UNREACHABLE, LampErrorClassifier.classify(new ConnectException("refused")));
        assertEquals(LampError.UNREACHABLE, LampErrorClassifier.classify(
                new AdbConnectException("Connection handshake failed", new ConnectException("refused"))));
        assertEquals(LampError.UNREACHABLE,
                LampErrorClassifier.classify(new SocketTimeoutException("connect timed out")));
        assertEquals(LampError.UNREACHABLE,
                LampErrorClassifier.classify(new AdbConnectionClosedException("closed", null)));
    }

    @Test
    public void readTimeoutDuringHandshakeMeansPendingDialog() {
        Throwable pending = new AdbConnectException("Connection handshake failed",
                new SocketTimeoutException("Read timed out"));
        assertEquals(LampError.UNAUTHORIZED, LampErrorClassifier.classify(pending));
        assertEquals(LampError.UNREACHABLE,
                LampErrorClassifier.classify(new SocketTimeoutException("Read timed out")));
    }

    @Test
    public void outputMapsToKinds() {
        assertNull(LampErrorClassifier.classifyOutput(0, "return_data : {5 }"));
        assertNull(LampErrorClassifier.classifyOutput(0, ""));
        assertEquals(LampError.COMMAND_FAILED, LampErrorClassifier.classifyOutput(1, "boom"));
        assertEquals(LampError.TOOL_MISSING, LampErrorClassifier.classifyOutput(127, "gmpfUnit: inaccessible or not found"));
        assertEquals(LampError.TOOL_MISSING, LampErrorClassifier.classifyOutput(0, "sh: gmpfUnit: Not Found"));
    }

    @Test
    public void kindTravelsInsideFetchResult() {
        FetchResult<String> failed = LampError.fail(LampError.UNAUTHORIZED, "pending");
        assertFalse(failed.isOk());
        assertSame(LampError.UNAUTHORIZED, LampError.of(failed));
        assertTrue(failed.error().contains("pending"));
        assertNull(LampError.of(FetchResult.ok("x")));
        assertNull(LampError.of(FetchResult.<String>err("plain")));
    }
}
