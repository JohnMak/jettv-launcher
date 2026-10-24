package com.johnmak.atvlauncher;

import android.graphics.Bitmap;
import android.os.Handler;
import android.util.LruCache;

import androidx.palette.graphics.Palette;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Resolves and caches the focused-tile glow colour for each app (see {@link GlowColor}).
 *
 * <p>Extraction is the expensive part (a {@link Palette} pass), so it never runs on the main thread and never on the
 * focus path: {@link #precompute} is called at adapter-bind/precompute time and does the work on a background
 * {@link Executor}. The result is kept in an {@link LruCache} keyed by {@code packageName@versionCode}, so it is
 * computed once per app and invalidates naturally when the app updates. {@link #colorFor} is the focus-path lookup:
 * it only reads the cache and returns {@link GlowColor#NEUTRAL} until the colour is ready (never blocks).
 */
final class TileGlow {

    /** Icons are already small (48x48, {@link AppLoader#ICON_SIZE}); cap Palette's cost regardless. */
    private static final int MAX_COLORS = 16;
    private static final int CACHE_ENTRIES = 128;

    private final Executor worker;
    private final Handler main;
    /** Called on the main thread with a package whose glow colour just resolved, so a focused tile can repaint. */
    private final Consumer<String> onResolved;
    private final LruCache<String, Integer> cache = new LruCache<>(CACHE_ENTRIES);
    /** Keys with an extraction in flight, so a package is never queued twice. Main-thread only. */
    private final Set<String> inFlight = new HashSet<>();

    TileGlow(Executor worker, Handler main, Consumer<String> onResolved) {
        this.worker = worker;
        this.main = main;
        this.onResolved = onResolved;
    }

    /** Resolved glow colour for an app, or {@link GlowColor#NEUTRAL} until {@link #precompute} has finished for it. */
    int colorFor(AppEntry entry) {
        if (entry == null) return GlowColor.NEUTRAL;
        Integer c = cache.get(entry.glowKey);
        return c != null ? c : GlowColor.NEUTRAL;
    }

    /**
     * Warms the cache for an app off the main thread. No-op when it is already resolved or already running, and when
     * the app has no icon to sample. Safe to call on every bind.
     */
    void precompute(AppEntry entry) {
        if (entry == null || entry.icon == null) return;
        final String key = entry.glowKey;
        if (cache.get(key) != null || !inFlight.add(key)) return;
        final Bitmap icon = entry.icon;
        final String pkg = entry.packageName;
        worker.execute(() -> {
            final int glow = extract(icon);
            main.post(() -> {
                cache.put(key, glow);
                inFlight.remove(key);
                onResolved.accept(pkg);
            });
        });
    }

    /** Dominant colour via Palette (background thread), mapped to the glow colour; neutral when none is found. */
    private static int extract(Bitmap icon) {
        Palette.Swatch dominant = Palette.from(icon).maximumColorCount(MAX_COLORS).generate().getDominantSwatch();
        return dominant != null ? GlowColor.glowFor(dominant.getRgb()) : GlowColor.NEUTRAL;
    }
}
