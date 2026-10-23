package com.johnmak.atvlauncher;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Launch counts per package over the last 30 days, from the system usage events (needs the usage-access app-op). */
final class LaunchStats {
    private static final String TAG = "AtvLaunchStats";
    private static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000;

    private LaunchStats() {
    }

    /** Blocking; empty map when usage access is not granted. */
    static Map<String, Long> last30Days(Context context) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        LaunchCounter counter = new LaunchCounter();
        try {
            UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    counter.onForeground(event.getPackageName());
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "usage events unavailable", ex);
            return Collections.emptyMap();
        }
        return counter.counts();
    }

    /** Blocking; ACTIVITY_RESUMED events since {@code fromMs} (wall clock), empty when usage access is not granted. */
    static List<RecentApps.Event> resumedSince(Context context, long fromMs) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        List<RecentApps.Event> result = new ArrayList<>();
        try {
            UsageEvents events = usm.queryEvents(fromMs, System.currentTimeMillis());
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() != RecentApps.RESUMED) continue;
                result.add(new RecentApps.Event(event.getPackageName(), event.getEventType(), event.getTimeStamp()));
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "usage events unavailable", ex);
            return Collections.emptyList();
        }
        return result;
    }
}
