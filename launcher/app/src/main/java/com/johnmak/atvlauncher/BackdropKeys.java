package com.johnmak.atvlauncher;

/** Identity of a page backdrop: a poster URI as is, or an installed app by package name. */
final class BackdropKeys {
    private static final String APP_PREFIX = "app:";

    private BackdropKeys() {
    }

    static String app(String packageName) {
        return APP_PREFIX + packageName;
    }

    static boolean isApp(String key) {
        return key.startsWith(APP_PREFIX);
    }

    /** Package name of an app key; the key must satisfy {@link #isApp}. */
    static String packageOf(String key) {
        return key.substring(APP_PREFIX.length());
    }
}
