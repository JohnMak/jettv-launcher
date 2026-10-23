package com.johnmak.atvlauncher;

/** Decides once at startup whether this device has the `gmpfUnit` brightness service. No ADB involved. */
final class BrightnessSupport {
    static final String GMPF_UNIT_PATH = "/system/bin/gmpfUnit";

    /** Answers whether a file exists. */
    interface FileProbe {
        boolean exists(String path);
    }

    /** Production probe backed by {@link java.io.File}. */
    static final FileProbe FILESYSTEM = path -> new java.io.File(path).exists();

    private BrightnessSupport() {}

    static boolean detect(FileProbe probe) {
        return probe.exists(GMPF_UNIT_PATH);
    }
}
