package com.johnmak.atvlauncher;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the weather shown: cached data instantly, a refresh when the home screen becomes active and every hour while
 * it stays active (what is due: {@link WeatherRefreshPolicy}), and the user-triggered refresh. The timer runs only
 * between {@link #start} and {@link #stop} (activity resumed / paused). The city itself is
 * chosen only in the launcher settings.
 */
final class WeatherController {
    private static final String TAG = "AtvWeather";

    interface Listener {
        void onDisplay(WeatherDisplay display);

        /** Short user-facing message (string resource id). */
        void onMessage(int stringRes);
    }

    private final WeatherPrefs prefs;
    private final Handler main;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final Runnable periodic = this::onPeriodic;
    private boolean started;
    private boolean busy;

    WeatherController(Context context, Handler main, Listener listener) {
        this.prefs = new WeatherPrefs(context);
        this.main = main;
        this.listener = listener;
    }

    void start() {
        started = true;
        render();
        main.removeCallbacks(periodic);
        refreshIfDue(WeatherRefreshPolicy.Trigger.ACTIVATED);
        main.postDelayed(periodic, WeatherRefreshPolicy.HOURLY_MS);
    }

    void stop() {
        started = false;
        main.removeCallbacks(periodic);
    }

    void release() {
        stop();
        worker.shutdownNow();
    }

    /** OK on the weather item with a city: fetch the weather now; a failure is reported. */
    void refreshNow() {
        refresh(true);
    }

    private void onPeriodic() {
        refreshIfDue(WeatherRefreshPolicy.Trigger.HOURLY);
        main.postDelayed(periodic, WeatherRefreshPolicy.HOURLY_MS);
    }

    private void refreshIfDue(WeatherRefreshPolicy.Trigger trigger) {
        if (WeatherRefreshPolicy.shouldRefresh(trigger, prefs.weather(), prefs.weatherLocationKey(),
                prefs.location(), System.currentTimeMillis())) {
            refresh(false);
        }
    }

    /** The snapshot is shown only for the city it was fetched for. */
    private void render() {
        GeoLocation location = prefs.location();
        Weather weather = location != null && location.key().equals(prefs.weatherLocationKey()) ? prefs.weather() : null;
        listener.onDisplay(WeatherDisplay.of(location, weather));
    }

    /** One network round on the worker; failures keep the previous data and are reported only when the user asked. */
    private void refresh(boolean report) {
        if (busy) return;
        GeoLocation location = prefs.location();
        if (location == null) return;
        busy = true;
        worker.execute(() -> {
            FetchResult<Weather> w = MetProvider.fetch(location);
            if (w.isOk()) prefs.saveWeather(w.value(), location.key());
            main.post(() -> {
                busy = false;
                if (!started) return;
                if (!w.isOk()) {
                    Log.w(TAG, "weather refresh failed: " + w.error());
                    if (report) listener.onMessage(R.string.weather_failed);
                }
                render();
            });
        });
    }
}
