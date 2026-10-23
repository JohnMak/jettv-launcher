package com.johnmak.atvlauncher;

import android.app.usage.UsageEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure: which apps were opened since boot most recent first. */
final class RecentApps {
    static final int RESUMED = UsageEvents.Event.ACTIVITY_RESUMED;

    static final class Event {
        final String pkg;
        final int type;
        final long timeMs;

        Event(String pkg, int type, long timeMs) {
            this.pkg = pkg;
            this.type = type;
            this.timeMs = timeMs;
        }
    }

    private RecentApps() {
    }

    /**
     * Packages with an ACTIVITY_RESUMED event after {@code bootMs} (wall clock),
     * restricted to {@code launchable} and excluding {@code ownPackage}, ordered by their latest such event, newest first.
     */
    static List<String> opened(List<Event> events, long bootMs, Set<String> launchable, String ownPackage) {
        Map<String, Long> latest = new HashMap<>();
        for (Event e : events) {
            if (e.type != RESUMED || e.timeMs <= bootMs) continue;
            if (e.pkg.equals(ownPackage) || !launchable.contains(e.pkg)) continue;
            latest.merge(e.pkg, e.timeMs, Math::max);
        }
        List<String> result = new ArrayList<>(latest.keySet());
        result.sort((a, b) -> {
            int byTime = Long.compare(latest.get(b), latest.get(a));
            return byTime != 0 ? byTime : a.compareTo(b);
        });
        return result;
    }
}
