package com.johnmak.atvlauncher;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;

import dadb.AdbAuthException;
import dadb.AdbConnectException;
import dadb.AdbTimeoutException;

/** Pure mapping from dadb exceptions and shell output to {@link ShellError}; no I/O. */
final class ShellErrorClassifier {
    private static final String HANDSHAKE = "handshake";
    private static final String NOT_FOUND = "not found";
    private static final int EXIT_NOT_FOUND = 127;

    private ShellErrorClassifier() {}

    /**
     * AdbAuthException = key rejected. A read timeout inside the connect handshake = adbd is waiting for
     * the user to answer the "Allow USB debugging?" dialog, so also UNAUTHORIZED. Everything else
     * (refused, connect timeout, reset, closed stream) = UNREACHABLE.
     */
    static ShellError classify(Throwable error) {
        boolean handshake = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof AdbAuthException) return ShellError.UNAUTHORIZED;
            if (t instanceof ConnectException) return ShellError.UNREACHABLE;
            if (t instanceof AdbConnectException && lower(t.getMessage()).contains(HANDSHAKE)) handshake = true;
            if (isReadTimeout(t) && handshake) return ShellError.UNAUTHORIZED;
        }
        return ShellError.UNREACHABLE;
    }

    /** null = success. The single place that decides "tool missing": exit 127 or "not found" text on a non-zero exit. */
    static ShellError classifyOutput(int exitCode, String output) {
        if (exitCode == 0) return null;
        if (exitCode == EXIT_NOT_FOUND || lower(output).contains(NOT_FOUND)) return ShellError.TOOL_MISSING;
        return ShellError.COMMAND_FAILED;
    }

    private static boolean isReadTimeout(Throwable t) {
        if (t instanceof AdbTimeoutException) return true;
        return t instanceof SocketTimeoutException && !lower(t.getMessage()).contains("connect");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
