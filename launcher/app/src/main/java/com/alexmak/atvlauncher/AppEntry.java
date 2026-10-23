package com.alexmak.atvlauncher;

import android.content.Intent;
import android.graphics.Bitmap;

/** One launchable app: immutable, tile bitmap is decoded once by {@link AppLoader}. */
final class AppEntry {
    final String packageName;
    final String label;
    final Intent launchIntent;
    final Bitmap tile;
    /** Small launcher icon for captions. */
    final Bitmap icon;

    AppEntry(String packageName, String label, Intent launchIntent, Bitmap tile, Bitmap icon) {
        this.packageName = packageName;
        this.label = label;
        this.launchIntent = launchIntent;
        this.tile = tile;
        this.icon = icon;
    }
}
