package com.johnmak.atvlauncher;

import android.content.Context;

import java.util.Collections;
import java.util.Map;

/** Process-wide app list and launch counts, so a second screen can paint instantly and refresh in the background. */
final class AppCache {
    private static AppLoader loader;
    private static volatile AppLoader.Result apps;
    private static volatile Map<String, Long> launches = Collections.emptyMap();

    private AppCache() {
    }

    static synchronized AppLoader loader(Context context) {
        if (loader == null) loader = new AppLoader(context);
        return loader;
    }

    /** Last loaded apps, or null before the first load finished. */
    static AppLoader.Result apps() {
        return apps;
    }

    static void setApps(AppLoader.Result result) {
        apps = result;
    }

    static Map<String, Long> launches() {
        return launches;
    }

    static void setLaunches(Map<String, Long> counts) {
        launches = counts;
    }
}
