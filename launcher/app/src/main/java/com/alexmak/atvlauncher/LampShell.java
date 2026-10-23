package com.alexmak.atvlauncher;

/** Runs `gmpfUnit` commands on this device. Blocking: call from a worker thread only. */
interface LampShell extends AutoCloseable {
    /** ok(stdout) on exit code 0; err carries a {@link LampError} kind. */
    FetchResult<String> exec(String command);

    @Override void close();
}
