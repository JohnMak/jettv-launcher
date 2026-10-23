package com.alexmak.atvlauncher;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;

import dadb.AdbAuthException;
import dadb.AdbConnectException;
import dadb.AdbTimeoutException;

/** Pure mapping from dadb exceptions and shell output to {@link LampError}; no I/O. */
final class LampErrorClassifier {
    private static final String HANDSHAKE = "handshake";

    private LampErrorClassifier() {}

    /**
     * AdbAuthException = key rejected. A read timeout inside the connect handshake = adbd is waiting for
     * the user to answer the "Allow USB debugging?" dialog, so also UNAUTHORIZED. Everything else
     * (refused, connect timeout, reset, closed stream) = UNREACHABLE.
     */
    static LampError classify(Throwable error) {
        boolean handshake = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof AdbAuthException) return LampError.UNAUTHORIZED;
            if (t instanceof ConnectException) return LampError.UNREACHABLE;
            if (t instanceof AdbConnectException && lower(t.getMessage()).contains(HANDSHAKE)) handshake = true;
            if (isReadTimeout(t) && handshake) return LampError.UNAUTHORIZED;
        }
        return LampError.UNREACHABLE;
    }

    /** null = success. "not found" wins over the exit code (a missing tool exits 127 with that text). */
    static LampError classifyOutput(int exitCode, String output) {
        if (lower(output).contains("not found")) return LampError.TOOL_MISSING;
        return exitCode == 0 ? null : LampError.COMMAND_FAILED;
    }

    private static boolean isReadTimeout(Throwable t) {
        if (t instanceof AdbTimeoutException) return true;
        return t instanceof SocketTimeoutException && !lower(t.getMessage()).contains("connect");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
