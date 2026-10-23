package com.alexmak.atvlauncher;

/** Typed failure kinds of the gmpf shell channel; carried inside {@link FetchResult#errorKind(Class)}. */
enum ShellError {
    /** Nothing listens on loopback adbd, or the connection timed out. */
    UNREACHABLE,
    /** adbd rejected our key, or the "Allow USB debugging?" dialog is still pending. */
    UNAUTHORIZED,
    /** Command ran and exited non-zero. */
    COMMAND_FAILED,
    /** The `gmpfUnit` tool does not exist on this device. */
    TOOL_MISSING;

    static <T> FetchResult<T> fail(ShellError kind, String message) {
        return FetchResult.err(kind + ": " + message, kind);
    }

    /** The kind of a failed shell result, or null for success / foreign errors. */
    static ShellError of(FetchResult<?> result) {
        return result.isOk() ? null : result.errorKind(ShellError.class);
    }
}
