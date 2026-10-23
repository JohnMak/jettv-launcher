package com.johnmak.atvlauncher;

import java.io.File;

/**
 * An APK that passed every {@link ApkVerifier} check. The constructor is package-private and only ApkVerifier calls it,
 * so {@link AdbSession#install} cannot be handed an unchecked file.
 */
final class VerifiedApk {
    private final File file;
    private final SemVer version;

    VerifiedApk(File file, SemVer version) {
        this.file = file;
        this.version = version;
    }

    File file() { return file; }

    SemVer version() { return version; }
}
