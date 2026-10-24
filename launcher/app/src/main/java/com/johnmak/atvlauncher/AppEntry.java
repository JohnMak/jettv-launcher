package com.johnmak.atvlauncher;

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
    /** Package version; part of the glow-colour cache key so it invalidates when the app updates ({@link TileGlow}). */
    final long versionCode;
    /** Glow-colour cache key ({@code packageName@versionCode}), precomputed so {@link TileGlow} allocates nothing per lookup. */
    final String glowKey;

    AppEntry(String packageName, String label, Intent launchIntent, Bitmap tile, Bitmap icon, long versionCode) {
        this.packageName = packageName;
        this.label = label;
        this.launchIntent = launchIntent;
        this.tile = tile;
        this.icon = icon;
        this.versionCode = versionCode;
        this.glowKey = packageName + '@' + versionCode;
    }
}
