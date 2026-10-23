package com.johnmak.atvlauncher;

/** Maps Android process names to package names. */
final class ProcessNames {
    private ProcessNames() {}

    /** `com.pkg:service` becomes `com.pkg`; names without ':' are returned unchanged. */
    static String packageOf(String processName) {
        int colon = processName.indexOf(':');
        return (colon < 0 ? processName : processName.substring(0, colon)).trim();
    }
}
