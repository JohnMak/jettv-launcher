package com.alexmak.atvlauncher;

import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Kinopoisk ratings and descriptions for feed items: disk cache first, else a lookup on two worker threads
 * (at most two requests at once). Without a token the whole feature is off: no threads, no network, no data.
 * Every method except the workers' own is for the main thread; {@code onInfo} is called on the main thread with the
 * lookup key of the item whose data just arrived.
 */
final class KinopoiskService {
    private static final String TAG = "AtvKinopoisk";
    private static final int MAX_CONCURRENT = 2;
    /** A failed lookup is not repeated for this long. */
    private static final long RETRY_AFTER_MS = 60_000;

    private final boolean enabled;
    private final File cacheFile;
    private final Handler main;
    private final Consumer<String> onInfo;
    private final KinopoiskClient client;
    private final ExecutorService pool;
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    /** Loaded by the first worker task; null until then. */
    private volatile KinopoiskCache cache;
    /** Lookups queued or running; the cache is saved when the last one ends. */
    private final AtomicInteger pending = new AtomicInteger();

    KinopoiskService(String token, File cacheFile, Handler main, Consumer<String> onInfo) {
        this.enabled = !token.isEmpty();
        this.cacheFile = cacheFile;
        this.main = main;
        this.onInfo = onInfo;
        this.client = enabled ? new KinopoiskClient(token) : null;
        this.pool = enabled ? Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
            Thread t = new Thread(r, "kinopoisk");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    static String key(FeedItem item) {
        return TitleMatch.key(item.title, item.year, TitleMatch.kindOf(item.season, item.episode, item.type));
    }

    /** Starts lookups for every item that has no fresh data yet. */
    void prefetch(List<FeedItem> items) {
        for (FeedItem item : items) info(item);
    }

    /** What is known right now (never blocks); starts a lookup when nothing fresh is stored. */
    KinopoiskInfo info(FeedItem item) {
        if (!enabled) return KinopoiskInfo.NONE;
        String key = key(item);
        if (key.isEmpty()) return KinopoiskInfo.NONE;
        long now = System.currentTimeMillis();
        KinopoiskCache c = cache;
        if (c != null) {
            KinopoiskInfo stored = c.get(key, now);
            if (stored != null) return stored;
        }
        Long retry = retryAt.get(key);
        if ((retry == null || now >= retry) && inFlight.add(key)) {
            pending.incrementAndGet();
            pool.execute(() -> lookup(key, item));
        }
        return KinopoiskInfo.NONE;
    }

    void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    /** Loads the cache file once; an unreadable file is logged and replaced by an empty cache. */
    private synchronized KinopoiskCache ensureCache() {
        if (cache == null) {
            FetchResult<KinopoiskCache> loaded = KinopoiskCache.load(cacheFile);
            if (!loaded.isOk()) Log.w(TAG, loaded.error());
            cache = loaded.isOk() ? loaded.value() : KinopoiskCache.empty(cacheFile);
        }
        return cache;
    }

    private void lookup(String key, FeedItem item) {
        KinopoiskCache c = ensureCache();
        long now = System.currentTimeMillis();
        boolean ok = true;
        if (c.get(key, now) == null) {
            TitleMatch.Kind kind = TitleMatch.kindOf(item.season, item.episode, item.type);
            FetchResult<KinopoiskInfo> result = client.lookup(item.title, item.year, kind);
            ok = result.isOk();
            if (ok) c.put(key, result.value(), now);
            else Log.w(TAG, "lookup failed: " + result.error());
        }
        if (pending.decrementAndGet() == 0) {
            FetchResult<Boolean> saved = c.save();
            if (!saved.isOk()) Log.w(TAG, saved.error());
        }
        boolean found = ok;
        main.post(() -> {
            inFlight.remove(key);
            if (found) {
                retryAt.remove(key);
                onInfo.accept(key);
            } else {
                retryAt.put(key, System.currentTimeMillis() + RETRY_AFTER_MS);
            }
        });
    }
}
