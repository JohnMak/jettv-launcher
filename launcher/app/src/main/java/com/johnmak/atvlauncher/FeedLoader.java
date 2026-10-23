package com.johnmak.atvlauncher;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.List;
import java.util.TreeSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads the TvProvider preview and watch-next programs of all apps (needs READ_TV_LISTINGS; the
 * provider rejects selections, so every row is read and filtered in {@link FeedBuilder}).
 * Blocking: call from a worker thread. A failing query or a bad row is logged and skipped.
 */
final class FeedLoader {
    private static final String TAG = "AtvFeed";

    static final class Feed {
        final List<FeedItem> watchNext;
        final List<FeedItem> mixed;
        /** Apps that publish to each row, before exclusion. */
        final Set<String> watchNextPublishers;
        final Set<String> mixedPublishers;
        final String signature;

        Feed(List<FeedItem> watchNext, List<FeedItem> mixed, Set<String> watchNextPublishers, Set<String> mixedPublishers) {
            this.watchNext = watchNext;
            this.mixed = mixed;
            this.watchNextPublishers = watchNextPublishers;
            this.mixedPublishers = mixedPublishers;
            StringBuilder sb = new StringBuilder();
            for (FeedItem i : watchNext) sb.append('W').append(i.intentUri).append(i.title).append(i.posterUri).append(i.season).append(i.episode).append('|');
            for (FeedItem i : mixed) sb.append('M').append(i.intentUri).append(i.title).append(i.posterUri).append(i.season).append(i.episode).append('|');
            sb.append("PW").append(new TreeSet<>(watchNextPublishers)).append("PM").append(new TreeSet<>(mixedPublishers));
            this.signature = sb.toString();
        }
    }

    private static final String[] PREVIEW_COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
            TvContract.PreviewPrograms.COLUMN_REVIEW_RATING,
            TvContract.PreviewPrograms.COLUMN_REVIEW_RATING_STYLE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_RELEASE_DATE,
            TvContract.PreviewPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
    };
    private static final String[] WATCH_NEXT_COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_REVIEW_RATING,
            TvContract.WatchNextPrograms.COLUMN_REVIEW_RATING_STYLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_RELEASE_DATE,
            TvContract.WatchNextPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
    };

    private final ContentResolver resolver;

    FeedLoader(ContentResolver resolver) {
        this.resolver = resolver;
    }

    Feed load(Set<String> allowedPackages, Set<String> excludedContinue, Set<String> excludedRecommendations,
              Map<String, Long> launches) {
        FeedBuilder builder = new FeedBuilder(allowedPackages, excludedContinue, excludedRecommendations, launches);
        read(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS, false, builder);
        read(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS, true, builder);
        return new Feed(builder.watchNext(), builder.mixed(), builder.watchNextPublishers(), builder.mixedPublishers());
    }

    private void read(Uri uri, String[] columns, boolean preview, FeedBuilder builder) {
        try (Cursor c = resolver.query(uri, columns, null, null, null)) {
            if (c == null) {
                Log.w(TAG, "no provider for " + uri);
                return;
            }
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                String intentUri = c.getString(3);
                if (pkg == null || intentUri == null || intentUri.isEmpty()) {
                    Log.w(TAG, "skipping row without package or intent in " + uri);
                    continue;
                }
                if (preview) {
                    builder.addPreview(new FeedItem(pkg, c.getString(1), c.getString(2), intentUri,
                            c.getInt(4), c.getLong(5), c.getInt(6), 0, c.getString(7), c.getInt(8),
                            c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13)));
                } else {
                    builder.addWatchNext(new FeedItem(pkg, c.getString(1), c.getString(2), intentUri,
                            c.getInt(4), 0, 0, c.getLong(5), c.getString(6), c.getInt(7),
                            c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12)));
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "query failed: " + uri, ex);
        }
    }
}
