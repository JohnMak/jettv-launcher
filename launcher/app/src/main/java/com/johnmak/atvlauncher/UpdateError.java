package com.johnmak.atvlauncher;

/** Typed failure kinds of the self-update pipeline; carried inside {@link FetchResult#errorKind(Class)}. */
enum UpdateError {
    /** The release feed or a download could not be reached. */
    NETWORK,
    /** The feed answered with something that is not an acceptable release. */
    FEED_INVALID,
    /** The download exceeded the size cap or ended short. */
    DOWNLOAD_FAILED,
    /** Checksum, package name, version or signing certificate check failed. */
    VERIFY_FAILED,
    /** adbd rejected our key or the "Allow USB debugging?" dialog is pending. */
    ADB_UNAUTHORIZED,
    /** Nothing listens on loopback adbd. */
    ADB_UNREACHABLE,
    /** The package manager refused the installation. */
    INSTALL_FAILED,
    /** A bug-class failure that escaped a collaborator. */
    INTERNAL;

    static <T> FetchResult<T> fail(UpdateError kind, String message) {
        return FetchResult.err(kind + ": " + message, kind);
    }

    /** The kind of a failed result, or null for success / foreign errors. */
    static UpdateError of(FetchResult<?> result) {
        return result.isOk() ? null : result.errorKind(UpdateError.class);
    }

    /** Maps the failure of an {@link AdbSession#install} call. */
    static UpdateError fromShell(ShellError shell) {
        switch (shell) {
            case UNAUTHORIZED: return ADB_UNAUTHORIZED;
            case UNREACHABLE: return ADB_UNREACHABLE;
            case COMMAND_FAILED:
            case TOOL_MISSING: return INSTALL_FAILED;
            default: throw new IllegalArgumentException(shell.name());
        }
    }
}
