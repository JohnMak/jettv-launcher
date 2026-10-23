package com.alexmak.atvlauncher;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;

/**
 * The page backdrop for posters and apps alike: a wanted key (see {@link BackdropKeys}) is debounced
 * ({@link BackdropPlanner}), its source image is blurred and dimmed on a worker ({@link BackdropBlur}), the tiny
 * result is cached and crossfaded in ({@link PosterBackdrop}). All methods run on the main thread.
 */
final class BackdropController {
    /** 128 blurred 160x90 ARGB_8888 bitmaps = 7.4 MB. */
    private static final int CACHE_ENTRIES = 128;

    interface Sources {
        /** Decoded source image for a key, or null when it is not available (yet). Called on the worker thread. */
        Bitmap load(String key);
    }

    private final PosterBackdrop view;
    private final BackdropPlanner planner = new BackdropPlanner();
    private final LruCache<String, Bitmap> blurred = new LruCache<>(CACHE_ENTRIES);
    private final Handler main;
    private final ExecutorService worker;
    private final Sources sources;
    private final Runnable apply = this::apply;
    private boolean released;

    BackdropController(ImageView target, Handler main, ExecutorService worker, Sources sources) {
        this.view = new PosterBackdrop(target);
        this.main = main;
        this.worker = worker;
        this.sources = sources;
    }

    /** Sets the backdrop that should be shown; null fades it out. Calling again with the same key retries a source that was not ready. */
    void want(String key) {
        long now = SystemClock.uptimeMillis();
        planner.want(key, now);
        main.removeCallbacks(apply);
        main.postDelayed(apply, planner.delayMs(now));
    }

    /** Drops the blurred bitmaps; they are rendered again on demand. */
    void evictCache() {
        blurred.evictAll();
    }

    /** Memory trim: drops the cache and the shown bitmap. The current look returns with the next {@link #want}. */
    void clear() {
        evictCache();
        planner.forgetApplied();
        view.clear();
    }

    /** Stops all pending work; call from onStop/onDestroy. */
    void release() {
        released = true;
        main.removeCallbacks(apply);
    }

    private void apply() {
        if (released || !planner.ready(SystemClock.uptimeMillis())) return;
        final String key = planner.wanted();
        if (key == null) {
            planner.commit();
            view.hide();
            return;
        }
        Bitmap cached = blurred.get(key);
        if (cached != null) {
            planner.commit();
            view.show(cached);
            return;
        }
        worker.execute(() -> {
            Bitmap source = sources.load(key);
            if (source == null) return;
            Bitmap result = BackdropBlur.render(source);
            main.post(() -> {
                if (released) return;
                blurred.put(key, result);
                if (key.equals(planner.wanted())) {
                    planner.commit();
                    view.show(result);
                }
            });
        });
    }
}
