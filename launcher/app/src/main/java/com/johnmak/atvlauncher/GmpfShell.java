package com.johnmak.atvlauncher;

/** Runs `gmpfUnit` commands on this device. Blocking: call from a worker thread only. */
interface GmpfShell extends AutoCloseable {
    /** ok(stdout) on exit code 0; err carries a {@link ShellError} kind. */
    FetchResult<String> exec(GmpfCommand command);

    /** Drops the connection; the next {@link #exec} reconnects lazily. */
    void disconnect();

    /** Final release: {@link #exec} after this fails instead of reconnecting. */
    @Override void close();
}
