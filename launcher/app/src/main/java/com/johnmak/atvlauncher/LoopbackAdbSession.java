package com.johnmak.atvlauncher;

import android.content.Context;

import java.io.File;
import java.io.IOException;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;
import dadb.InstallResult;

/**
 * {@link AdbSession} over the device's own adbd on 127.0.0.1:5555 (dadb). The RSA key lives in the app's
 * private files; the first connection shows a one-time "Allow USB debugging?" dialog. One connection is
 * kept; a failure drops it so the next call reconnects. Blocking, worker thread only; calls are serialized.
 * There is exactly one instance per process ({@link #shared}), used by brightness and by the updater.
 */
final class LoopbackAdbSession implements AdbSession {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    /** Also how long the user has to answer the first-time authorization dialog. */
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    private final File keyDir;
    private AdbKeyPair keys;
    private Dadb adb;

    private static LoopbackAdbSession shared;

    /** The process-wide session; the single dadb connection is never duplicated. */
    static synchronized LoopbackAdbSession shared(Context context) {
        if (shared == null) shared = new LoopbackAdbSession(context.getApplicationContext());
        return shared;
    }

    private LoopbackAdbSession(Context context) {
        this.keyDir = new File(context.getFilesDir(), "adb");
    }

    @Override
    public synchronized FetchResult<String> exec(ShellCommand command) {
        try {
            AdbShellResponse response = connection().shell(command.shellLine());
            String output = response.getAllOutput();
            if (command.verb().isPackageManagement()) {
                return PmOutputParser.parse(command.verb(), response.getExitCode(), response.getOutput(), response.getErrorOutput());
            }
            ShellError failure = ShellErrorClassifier.classifyOutput(response.getExitCode(), output);
            if (failure == null) return FetchResult.ok(response.getOutput());
            return ShellError.fail(failure, "rc=" + response.getExitCode() + " " + output.trim());
        } catch (Exception e) { // I/O boundary: nothing may escape as an untyped exception
            dropConnection();
            return ShellError.fail(ShellErrorClassifier.classify(e), String.valueOf(e.getMessage()));
        }
    }

    @Override
    public synchronized FetchResult<String> install(VerifiedApk apk) {
        try {
            InstallResult result = connection().install(apk.file(), "-r");
            if (result instanceof InstallResult.Failure) {
                return ShellError.fail(ShellError.COMMAND_FAILED, ((InstallResult.Failure) result).getReason());
            }
            return FetchResult.ok("installed");
        } catch (Exception e) { // I/O boundary: nothing may escape as an untyped exception
            dropConnection();
            return ShellError.fail(ShellErrorClassifier.classify(e), String.valueOf(e.getMessage()));
        }
    }

    private Dadb connection() throws IOException {
        if (adb == null) adb = Dadb.create(HOST, PORT, keys(), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
        return adb;
    }

    private AdbKeyPair keys() throws IOException {
        if (keys != null) return keys;
        File priv = new File(keyDir, "adbkey");
        File pub = new File(keyDir, "adbkey.pub");
        if (!priv.isFile() || !pub.isFile()) {
            if (!keyDir.isDirectory() && !keyDir.mkdirs()) throw new IOException("cannot create " + keyDir);
            AdbKeyPair.generate(priv, pub);
        }
        keys = AdbKeyPair.read(priv, pub);
        return keys;
    }

    private void dropConnection() {
        if (adb == null) return;
        try {
            adb.close();
        } catch (Exception ignored) {
            // already broken; the next exec reconnects
        }
        adb = null;
    }

    @Override
    public synchronized void disconnect() {
        dropConnection();
    }
}
