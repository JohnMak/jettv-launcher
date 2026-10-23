package com.johnmak.atvlauncher;

/**
 * The one ADB channel of the process (the device's own adbd on loopback), with exactly two typed operations:
 * a validated `gmpfUnit` command and the installation of a verified APK. No free-form shell string exists.
 * Blocking: call from a worker thread only.
 */
interface AdbSession {
    /** ok(stdout) on exit code 0; err carries a {@link ShellError} kind. */
    FetchResult<String> exec(ShellCommand command);

    /** Replaces the installed app with {@code apk} (`-r`); ok(output) on success, err carries a {@link ShellError} kind. */
    FetchResult<String> install(VerifiedApk apk);

    /** Drops the connection; the next operation reconnects lazily. */
    void disconnect();
}
