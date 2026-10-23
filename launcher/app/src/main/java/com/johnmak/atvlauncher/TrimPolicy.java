package com.johnmak.atvlauncher;

import android.content.ComponentCallbacks2;

/** When the launcher drops its in-memory caches. */
final class TrimPolicy {
    private TrimPolicy() {
    }

    /** True from {@code TRIM_MEMORY_UI_HIDDEN} (the UI is no longer visible) up to {@code TRIM_MEMORY_COMPLETE}. */
    static boolean evictsCaches(int level) {
        return level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
    }
}
