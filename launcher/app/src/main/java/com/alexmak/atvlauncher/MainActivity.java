package com.alexmak.atvlauncher;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.TimeZone;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "AtvLauncher";
    private static final long FEED_DEBOUNCE_MS = 1000;
    /** Favorites layout constants (dp): tile width limits and the smallest gap before the row scrolls. */
    private static final int TILE_MIN_DP = 96;
    private static final int TILE_MAX_DP = 220;
    private static final int TILE_GAP_DP = 16;
    private static final long VPN_TIMEOUT_MS = 15_000;
    private static final long VPN_ERROR_MS = 3_000;
    private static final int DOT_ON = 0xFF34C759;
    private static final int DOT_OFF = 0xFF636366;
    private static final int DOT_BUSY = 0xFFFF9F0A;
    private static final int DOT_ERROR = 0xFFFF453A;

    private static final int TOP_ITEM_GAP_DP = 28;
    /** Glass margin around a top-bar item (dp): wide enough to hold the gap between items. */
    private static final int TOP_GLASS_PAD_X_DP = 12;
    private static final int TOP_GLASS_PAD_Y_DP = 2;
    private static final int WEATHER_TOP_MARGIN_DP = 12;
    private static final int WEATHER_GLASS_RADIUS_DP = 14;

    private enum Mode { NAVIGATE, MENU, MOVE }

    // Views
    private View root;
    private View page;
    private View topBlock;
    private TextView moveHint;
    private FeedInfoField watchInfo;
    private FeedInfoField mixedInfo;
    private TextView date;
    private View watchHeading;
    private View mixedHeading;
    private RecyclerView favoritesRow;
    private RecyclerView watchRow;
    private RecyclerView mixedRow;
    private GlassView glass;
    private BackdropController backdrop;
    /** The backdrop was dropped by a memory trim and must be asked for again in onStart. */
    private boolean backdropTrimmed;
    private GlassMenu menu;
    private TopBarItem vpnItem;
    private TopBarItem wifiItem;
    private TopBarItem launcherSettingsItem;
    private TopBarItem settingsItem;
    private WeatherItem weatherItem;
    private TextClock clock;
    private WeatherPrefs zonePrefs;
    private SimpleDateFormat dateFormat;
    private WeatherController weather;
    private IdleController idle;
    /** UI scale (percent) this activity was created with. */
    private int uiScalePercent;

    // Data
    private AppLoader.Result apps;
    private FavoritesStore favorites;
    /** Favorites only, in saved order. */
    private final List<AppEntry> favEntries = new ArrayList<>();
    private List<RecentApps.Event> usageEvents = new ArrayList<>();
    /** Packages opened since boot, newest first. */
    private List<String> opened = new ArrayList<>();
    private FavoritesAdapter favAdapter;
    /** Packages that never appear in the row's continuation: the AllApps header apps (favorites are excluded per rebuild). */
    private Set<String> headerPackages;
    /** Launch counts the continuation was last sorted by. */
    private Map<String, Long> rankedBy;
    private FeedAdapter watchAdapter;
    private FeedAdapter mixedAdapter;
    private String lastSignature = "";
    private String lastFeedSignature = "";
    private float density;

    // Selection (virtual focus: real Android focus is never used, so D-pad is fully deterministic)
    private final FocusModel focus = new FocusModel();
    private final CenterKey centerKey = new CenterKey();
    private Mode mode = Mode.NAVIGATE;
    private float pageShift;
    private boolean focusVisualsPending;
    private final Runnable focusVisualsRunnable = () -> {
        focusVisualsPending = false;
        onFocusChanged(true);
    };
    /** The selection the views were last brought to; null = views must be set up completely (first run, data reload). */
    private SelectionDelta.Selection appliedSelection;
    private final RectF glassTarget = new RectF();
    private final RectF rowRect = new RectF();
    private RowScroller favoritesScroller;
    private RowScroller watchScroller;
    private RowScroller mixedScroller;
    private boolean feedShown;
    private boolean hintShown;

    // Services
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService posterWorker = Executors.newFixedThreadPool(3);
    private PosterCache posters;
    private FeedLoader feedLoader;
    private KinopoiskService kinopoisk;
    private NetworkStatus network;
    private LauncherSettingsStore settingsStore;
    /** VPN app of the top-bar button resolved from the settings; empty = button hidden. */
    private String vpnPackage = "";
    private String dateLang;

    // VPN state
    private boolean vpnOn;
    private boolean vpnSwitching;
    private boolean vpnError;
    private boolean vpnTargetOn;
    private ObjectAnimator vpnPulse;
    private final Runnable vpnTimeout = this::onVpnTimeout;
    private final Runnable vpnErrorClear = () -> {
        vpnError = false;
        renderVpn();
    };
    private final Runnable refreshRunnable = this::refreshApps;
    private final Runnable feedRunnable = this::refreshFeed;
    private boolean selectionPending;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.removeCallbacks(refreshRunnable);
            main.postDelayed(refreshRunnable, 400);
        }
    };
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyZone();
        }
    };
    private final ContentObserver feedObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            main.removeCallbacks(feedRunnable);
            main.postDelayed(feedRunnable, FEED_DEBOUNCE_MS);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        Resources res = getResources();
        dateLang = res.getString(R.string.date_language);
        dateFormat = new SimpleDateFormat("EEEE, d MMMM", new Locale(dateLang));
        zonePrefs = new WeatherPrefs(this);
        settingsStore = new LauncherSettingsStore(this);
        uiScalePercent = settingsStore.load().uiScalePercent;

        headerPackages = new HashSet<>(java.util.Arrays.asList(res.getStringArray(R.array.app_header_packages)));

        root = findViewById(R.id.root);
        page = findViewById(R.id.page);
        topBlock = findViewById(R.id.top_block);
        moveHint = findViewById(R.id.move_hint);
        watchInfo = findViewById(R.id.watch_info);
        mixedInfo = findViewById(R.id.mixed_info);
        date = findViewById(R.id.date);
        watchHeading = findViewById(R.id.watch_heading);
        mixedHeading = findViewById(R.id.mixed_heading);
        favoritesRow = findViewById(R.id.favorites);
        watchRow = findViewById(R.id.watch_row);
        mixedRow = findViewById(R.id.mixed_row);
        glass = findViewById(R.id.glass);
        clock = findViewById(R.id.clock);
        FocusDim.applyAlpha(clock, FocusDim.Role.AMBIENT, false, false);
        FocusDim.applyAlpha(date, FocusDim.Role.AMBIENT, false, false);
        menu = findViewById(R.id.menu);

        setupWallpaper();
        setupTopBar();
        idle = new IdleController((FrameLayout) root, main, () -> backdrop.evictCache());
        setupWeather();

        posters = new PosterCache(getContentResolver(), getCacheDir());
        AppBackdropImage appImages = new AppBackdropImage(getPackageManager());
        backdrop = new BackdropController(findViewById(R.id.backdrop), main, worker,
                key -> BackdropKeys.isApp(key) ? appImages.load(BackdropKeys.packageOf(key)) : posters.loadForBackdrop(key, BackdropBlur.W * 2, BackdropBlur.H * 2));
        favAdapter = new FavoritesAdapter(density);
        watchAdapter = new FeedAdapter(posters, posterWorker, main, density, this::updateBackdrop, this::appIcon);
        mixedAdapter = new FeedAdapter(posters, posterWorker, main, density, this::updateBackdrop, this::appIcon);
        setupRow(favoritesRow, favAdapter, FocusModel.Zone.FAVORITES);
        favoritesRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) relayoutFavorites();
        });
        setupRow(watchRow, watchAdapter, FocusModel.Zone.WATCH_NEXT);
        setupRow(mixedRow, mixedAdapter, FocusModel.Zone.MIXED);

        feedLoader = new FeedLoader(getContentResolver());
        kinopoisk = new KinopoiskService(BuildConfig.KINOPOISK_TOKEN, new File(getFilesDir(), "kinopoisk_cache.json"),
                main, this::onKinopoiskInfo);
        network = new NetworkStatus(this, new NetworkStatus.Listener() {
            @Override
            public void onVpnChanged(boolean on) {
                onVpnState(on);
            }

            @Override
            public void onLinkChanged(LinkState link) {
                renderLink(link);
                applySelectionAfterLayout();
            }
        });
        renderVpn();
    }

    // ---------------------------------------------------------------- setup

    private void setupWallpaper() {
        String name = getResources().getString(R.string.wallpaper_drawable_name);
        if (name.isEmpty()) return;
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id == 0) {
            Log.w(TAG, "wallpaper_drawable_name '" + name + "' not found in res/drawable");
            return;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), id, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, bounds.outWidth / 1920);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        ((ImageView) findViewById(R.id.wallpaper))
                .setImageBitmap(BitmapFactory.decodeResource(getResources(), id, opts));
    }

    private void setupTopBar() {
        LinearLayout bar = findViewById(R.id.top_bar);
        vpnItem = new TopBarItem(this, true, false, true);
        wifiItem = new TopBarItem(this, false, true, true);
        launcherSettingsItem = new TopBarItem(this, false, true, false);
        launcherSettingsItem.setIcon(R.drawable.ic_sliders_horizontal);
        settingsItem = new TopBarItem(this, false, true, false);
        settingsItem.setIcon(R.drawable.ic_settings);
        for (TopBarItem item : new TopBarItem[]{vpnItem, wifiItem, launcherSettingsItem, settingsItem}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * density));
            lp.setMarginStart(Math.round(TOP_ITEM_GAP_DP * density));
            bar.addView(item, lp);
        }
        renderLink(LinkState.offline());
    }

    private void setupWeather() {
        weatherItem = new WeatherItem(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(WEATHER_TOP_MARGIN_DP * density);
        ((LinearLayout) findViewById(R.id.left_column)).addView(weatherItem, lp);
        weather = new WeatherController(this, main, new WeatherController.Listener() {
            @Override
            public void onDisplay(WeatherDisplay display) {
                weatherItem.render(display);
                idle.screensaver().setWeatherText(display.starfieldText());
                idle.screensaver().setWeatherIcon(display.hasWeather
                        ? getDrawable(WeatherIcons.drawable(display.icon)) : null);
                applySelectionAfterLayout();
            }

            @Override
            public void onMessage(int stringRes) {
                Toast.makeText(MainActivity.this, stringRes, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static int wifiGlyph(LinkState.Icon icon) {
        switch (icon) {
            case WIFI_ZERO: return R.drawable.ic_wifi_zero;
            case WIFI_LOW: return R.drawable.ic_wifi_low;
            case WIFI_HIGH: return R.drawable.ic_wifi_high;
            case WIFI_FULL: return R.drawable.ic_wifi;
            default: throw new IllegalArgumentException("not a Wi-Fi icon: " + icon);
        }
    }

    private void renderLink(LinkState link) {
        switch (link.icon) {
            case ETHERNET:
                wifiItem.setIcon(R.drawable.ic_ethernet_port);
                wifiItem.setLabel(getString(R.string.bar_ethernet));
                break;
            case OFFLINE:
                wifiItem.setIcon(R.drawable.ic_wifi_off);
                wifiItem.setLabel(getString(R.string.bar_offline));
                break;
            default:
                wifiItem.setIcon(wifiGlyph(link.icon));
                wifiItem.setLabel(link.ssid != null ? link.ssid : getString(R.string.bar_wifi));
        }
    }


    private void setupRow(RecyclerView row, RecyclerView.Adapter<?> adapter, FocusModel.Zone zone) {
        row.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        row.setHasFixedSize(true);
        row.setItemAnimator(null);
        row.setAdapter(adapter);
        RowScroller scroller = new RowScroller(row, () -> {
            if (focus.zone() == zone) applySelection(false);
        });
        switch (zone) {
            case FAVORITES: favoritesScroller = scroller; break;
            case WATCH_NEXT: watchScroller = scroller; break;
            default: mixedScroller = scroller;
        }
        row.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && focus.zone() == zone) applySelection(false);
            }
        });
    }

    // ---------------------------------------------------------------- lifecycle

    /** Back from the launcher settings: a changed UI scale needs a fresh activity (density is fixed at attach time). */
    @Override
    protected void onRestart() {
        super.onRestart();
        if (settingsStore.load().uiScalePercent != uiScalePercent) recreate();
    }

    @Override
    protected void onStart() {
        super.onStart();
        favorites = FavoritesPrefs.open(this);
        resetFavoritesRow();
        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkg.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkg.addDataScheme("package");
        registerReceiver(packageReceiver, pkg); // system broadcasts only: no export flag needed
        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time);
        getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, feedObserver);
        getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, feedObserver);
        getContentResolver().registerContentObserver(TvContract.Channels.CONTENT_URI, true, feedObserver);
        network.register();
        applyZone();
        idle.start();
        updateVpnItemVisibility();
        if (backdropTrimmed) {
            backdropTrimmed = false;
            updateBackdrop();
        }
        refreshApps();
        refreshFeed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetFavoritesRow();
    }

    /** HOME or a fresh start: the row is back at its start and focus is no further than the apps button. */
    private void resetFavoritesRow() {
        focus.limitColumn(FocusModel.Zone.FAVORITES, favAdapter.buttonIndex());
        favoritesScroller.cancel();
        favoritesRow.scrollToPosition(0);
        applySelectionAfterLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        weather.start();
        refreshUsage();
    }

    @Override
    protected void onPause() {
        weather.stop();
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        getContentResolver().unregisterContentObserver(feedObserver);
        network.unregister();
        idle.stop();
        main.removeCallbacks(refreshRunnable);
        main.removeCallbacks(feedRunnable);
        endMenuAndMove();
        super.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!TrimPolicy.evictsCaches(level)) return;
        posters.evictAll();
        backdrop.clear();
        backdropTrimmed = true;
        ShadowView.evictCache();
    }

    @Override
    protected void onDestroy() {
        page.removeCallbacks(focusVisualsRunnable);
        main.removeCallbacksAndMessages(null);
        backdrop.release();
        stopVpnPulse();
        weather.release();
        kinopoisk.shutdown();
        worker.shutdownNow();
        posterWorker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- data

    private void refreshApps() {
        worker.execute(() -> {
            AppLoader.Result result = AppCache.loader(this).load();
            AppCache.setApps(result);
            main.post(() -> {
                if (!isDestroyed()) applyApps(result);
            });
        });
    }

    private void applyApps(AppLoader.Result result) {
        apps = result;
        favorites.retainOnly(result.byPackage.keySet());
        updateVpnItemVisibility();
        String sig = signature();
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;
        rebuildFavorites();
        refreshFeed();
    }

    private String signature() {
        return apps.signature + '#' + String.join(",", favorites.list());
    }

    private void refreshUsage() {
        final long bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        worker.execute(() -> {
            List<RecentApps.Event> events = LaunchStats.resumedSince(this, bootWall);
            main.post(() -> {
                if (!isDestroyed()) applyUsage(events);
            });
        });
    }

    private void applyUsage(List<RecentApps.Event> events) {
        usageEvents = events;
        if (apps == null) return; // the first applyApps builds the row from these events
        if (computeOpened().equals(opened)) return;
        rebuildFavorites();
    }

    private List<String> computeOpened() {
        long bootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return RecentApps.opened(usageEvents, bootWall, apps.byPackage.keySet(), getPackageName());
    }

    private void rebuildFavorites() {
        favEntries.clear();
        for (String pkg : favorites.list()) favEntries.add(apps.byPackage.get(pkg));
        opened = computeOpened();
        rankedBy = AppCache.launches();
        Set<String> notFrequent = new HashSet<>(headerPackages);
        notFrequent.addAll(favorites.list());
        List<AppEntry> frequent = AppGrouping.exclude(apps.apps, a -> a.packageName, notFrequent);
        frequent.sort(UsageRanking.comparator(a -> a.packageName, a -> a.label, rankedBy));
        favAdapter.setItems(favEntries, frequent, new HashSet<>(opened));
        relayoutFavorites();
        lastSignature = signature();
        updateCounts();
    }

    /** Recomputes tile size and spacing for the row's content width and the current item count. */
    private void relayoutFavorites() {
        int content = favoritesRow.getWidth() - favoritesRow.getPaddingLeft() - favoritesRow.getPaddingRight();
        if (content <= 0) return;
        FavoritesRowLayout geometry = FavoritesRowLayout.compute(content, favAdapter.fitCount(), favAdapter.tileCount(),
                Math.round(TILE_GAP_DP * density), Math.round(TILE_MIN_DP * density),
                Math.round(TILE_MAX_DP * density));
        favoritesScroller.cancel();
        favAdapter.setGeometry(geometry);
        applySelectionAfterLayout();
    }

    private void refreshFeed() {
        if (apps == null) return;
        final Set<String> allowed = new HashSet<>(apps.byPackage.keySet());
        worker.execute(() -> {
            Map<String, Long> launches = LaunchStats.last30Days(this);
            AppCache.setLaunches(launches);
            FeedLoader.Feed feed = feedLoader.load(allowed, launches);
            main.post(() -> {
                if (isDestroyed()) return;
                if (!launches.equals(rankedBy)) rebuildFavorites();
                applyFeed(feed);
            });
        });
    }

    private void applyFeed(FeedLoader.Feed feed) {
        if (feed.signature.equals(lastFeedSignature)) return;
        lastFeedSignature = feed.signature;
        watchScroller.cancel();
        mixedScroller.cancel();
        watchAdapter.setItems(feed.watchNext);
        mixedAdapter.setItems(feed.mixed);
        kinopoisk.prefetch(feed.watchNext);
        kinopoisk.prefetch(feed.mixed);
        updateCounts();
    }

    /** Pushes row sizes into the focus model and shows or hides empty feed rows. */
    private void updateCounts() {
        focus.setCounts(1, visibleTopItems().size(), favAdapter.tileCount(),
                watchAdapter.getItemCount(), mixedAdapter.getItemCount());
        setRowVisible(watchHeading, watchRow, watchInfo, watchAdapter.getItemCount() > 0);
        setRowVisible(mixedHeading, mixedRow, mixedInfo, mixedAdapter.getItemCount() > 0);
        appliedSelection = null; // data reload: every view gets its state again
        onFocusChanged(false);
    }

    private static void setRowVisible(View heading, View row, View info, boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        heading.setVisibility(vis);
        row.setVisibility(vis);
        info.setVisibility(vis);
    }

    // ---------------------------------------------------------------- selection rendering

    private List<TopBarItem> visibleTopItems() {
        List<TopBarItem> items = new ArrayList<>();
        if (vpnItem.getVisibility() == View.VISIBLE) items.add(vpnItem);
        items.add(wifiItem);
        items.add(launcherSettingsItem);
        items.add(settingsItem);
        return items;
    }

    private boolean inFeed() {
        return focus.zone() == FocusModel.Zone.WATCH_NEXT || focus.zone() == FocusModel.Zone.MIXED;
    }

    private void onFocusChanged(boolean animate) {
        updateInfo();
        updateBackdrop();
        applySelection(animate);
    }

    /**
     * Key repeats advance the FocusModel at once but their visuals are applied at most once per frame, so a fast
     * repeat never queues more rendering work than the screen can show.
     */
    private void scheduleFocusVisuals() {
        if (focusVisualsPending) return;
        focusVisualsPending = true;
        page.postOnAnimation(focusVisualsRunnable);
    }

    /** Page background follows the focused app tile or poster; the top bar keeps the current backdrop. */
    private void updateBackdrop() {
        switch (focus.zone()) {
            case WEATHER:
            case TOP:
                return;
            case FAVORITES:
                AppEntry entry = favAdapter.entry(focus.index());
                backdrop.want(entry != null ? BackdropKeys.app(entry.packageName) : null);
                break;
            case WATCH_NEXT:
                backdrop.want(watchAdapter.item(focus.index()).posterUri);
                break;
            default:
                backdrop.want(mixedAdapter.item(focus.index()).posterUri);
        }
    }

    /** Fills the focused row's info field with the focused card (source app, title, rating); the move hint is separate. */
    private void updateInfo() {
        switch (focus.zone()) {
            case WATCH_NEXT:
                fillInfo(watchInfo, watchAdapter.item(focus.index()));
                break;
            case MIXED:
                fillInfo(mixedInfo, mixedAdapter.item(focus.index()));
                break;
            default:
                break;
        }
    }

    private Bitmap appIcon(String packageName) {
        AppEntry source = apps.byPackage.get(packageName);
        return source != null ? source.icon : null;
    }

    private void fillInfo(FeedInfoField field, FeedItem item) {
        field.setContent(item.title,
                FeedInfoLine.season(item.season, item.episode), item.rating, kinopoisk.info(item));
    }

    /** Kinopoisk data arrived for {@code key}: refresh the info field when that card is the focused one. */
    private void onKinopoiskInfo(String key) {
        if (isDestroyed()) return;
        FeedItem focused;
        switch (focus.zone()) {
            case WATCH_NEXT:
                focused = watchAdapter.item(focus.index());
                break;
            case MIXED:
                focused = mixedAdapter.item(focus.index());
                break;
            default:
                return;
        }
        if (KinopoiskService.key(focused).equals(key)) updateInfo();
    }

    /** Re-applies the selection once the pending layout pass is done, so new or resized views have real bounds. */
    private void applySelectionAfterLayout() {
        if (selectionPending) return;
        selectionPending = true;
        page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                page.getViewTreeObserver().removeOnPreDrawListener(this);
                selectionPending = false;
                applySelection(false);
                return true;
            }
        });
    }

    /** Moves the glass to the current selection, scales tiles and shifts the page for the active zone. */
    private void applySelection(boolean animate) {
        boolean feed = inFeed();
        float shift = feed ? -FeedPageShift.shiftDp(focus.zone(), watchAdapter.getItemCount() > 0) * density : 0;
        if (pageShift != shift) {
            pageShift = shift;
            page.animate().translationY(shift).setDuration(300).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (feedShown != feed) {
            feedShown = feed;
            topBlock.animate().alpha(feed ? 0f : 1f).setDuration(300).start();
        }
        boolean hint = mode == Mode.MOVE;
        if (hintShown != hint) {
            hintShown = hint;
            moveHint.animate().alpha(hint ? 1f : 0f).setDuration(200).start();
        }
        SelectionDelta.Selection next = currentSelection();
        SelectionDelta delta = SelectionDelta.between(appliedSelection, next);
        boolean full = appliedSelection == null;
        appliedSelection = next;
        favAdapter.setSelected(focus.zone() == FocusModel.Zone.FAVORITES ? focus.index() : -1);
        watchAdapter.setSelected(focus.zone() == FocusModel.Zone.WATCH_NEXT ? focus.index() : -1);
        mixedAdapter.setSelected(focus.zone() == FocusModel.Zone.MIXED ? focus.index() : -1);
        if (delta.rowChanged) {
            watchInfo.setShown(FeedInfoLine.visible(FocusModel.Zone.WATCH_NEXT, focus.zone()));
            mixedInfo.setShown(FeedInfoLine.visible(FocusModel.Zone.MIXED, focus.zone()));
        }
        if (full) applyAllViews(animate);
        else for (SelectionDelta.ViewRef ref : delta.changedViews) applyView(ref, next, animate);

        float radius;
        switch (focus.zone()) {
            case WEATHER:
                if (!Tiles.locate(weatherItem, page, glassTarget)) return;
                glassTarget.inset(-TOP_GLASS_PAD_X_DP * density, -TOP_GLASS_PAD_Y_DP * 2 * density);
                radius = WEATHER_GLASS_RADIUS_DP * density;
                break;
            case TOP: {
                TopBarItem item = visibleTopItems().get(focus.index());
                if (!Tiles.locate(item, page, glassTarget)) return;
                glassTarget.inset(-TOP_GLASS_PAD_X_DP * density, -TOP_GLASS_PAD_Y_DP * density);
                radius = glassTarget.height() / 2;
                break;
            }
            case FAVORITES:
                if (!locateFavorite(focus.index(), glassTarget)) return;
                radius = Tiles.surround(glassTarget, density, 10);
                break;
            case WATCH_NEXT:
                if (!locateInRow(watchRow, watchScroller, watchAdapter, focus.index(), glassTarget)) return;
                radius = Tiles.grow(glassTarget, density, 12);
                break;
            default:
                if (!locateInRow(mixedRow, mixedScroller, mixedAdapter, focus.index(), glassTarget)) return;
                radius = Tiles.grow(glassTarget, density, 12);
        }
        glass.moveTo(glassTarget, radius, animate);
    }

    private SelectionDelta.Selection currentSelection() {
        FocusModel.Zone[] zones = FocusModel.Zone.values();
        int[] index = new int[zones.length];
        int[] size = new int[zones.length];
        for (FocusModel.Zone z : zones) {
            int row = SelectionDelta.Row.valueOf(z.name()).ordinal();
            index[row] = focus.index(z);
        }
        size[SelectionDelta.Row.WEATHER.ordinal()] = 1;
        size[SelectionDelta.Row.TOP.ordinal()] = visibleTopItems().size();
        size[SelectionDelta.Row.FAVORITES.ordinal()] = favAdapter.tileCount();
        size[SelectionDelta.Row.WATCH_NEXT.ordinal()] = watchAdapter.getItemCount();
        size[SelectionDelta.Row.MIXED.ordinal()] = mixedAdapter.getItemCount();
        return new SelectionDelta.Selection(SelectionDelta.Row.valueOf(focus.zone().name()), index, size);
    }

    /** Visual focus state of one view (dim, scale, alpha) for the given selection; views not laid out get it when bound. */
    private void applyView(SelectionDelta.ViewRef ref, SelectionDelta.Selection sel, boolean animate) {
        boolean focused = sel.zone == ref.row && sel.index[ref.row.ordinal()] == ref.index;
        switch (ref.row) {
            case WEATHER:
                FocusDim.applyAlpha(weatherItem, FocusDim.Role.TOP_BAR, focused, animate);
                break;
            case TOP: {
                List<TopBarItem> items = visibleTopItems();
                if (ref.index < items.size()) FocusDim.applyAlpha(items.get(ref.index), FocusDim.Role.TOP_BAR, focused, animate);
                break;
            }
            case FAVORITES: {
                RecyclerView.ViewHolder vh = favoritesRow.findViewHolderForAdapterPosition(ref.index);
                if (vh != null) ((FavoritesAdapter.Holder) vh).image.setFocused(focused, animate);
                break;
            }
            case WATCH_NEXT: {
                RecyclerView.ViewHolder vh = watchRow.findViewHolderForAdapterPosition(ref.index);
                if (vh != null) Tiles.setCardSelected(vh.itemView, focused, animate);
                break;
            }
            default: {
                RecyclerView.ViewHolder vh = mixedRow.findViewHolderForAdapterPosition(ref.index);
                if (vh != null) Tiles.setCardSelected(vh.itemView, focused, animate);
            }
        }
    }

    /** Brings every view to its state (first run, data reload); unfocused views already at their level are not animated. */
    private void applyAllViews(boolean animate) {
        boolean top = focus.zone() == FocusModel.Zone.TOP;
        List<TopBarItem> items = visibleTopItems();
        for (TopBarItem item : new TopBarItem[]{vpnItem, wifiItem, launcherSettingsItem, settingsItem}) {
            FocusDim.applyAlpha(item, FocusDim.Role.TOP_BAR, top && items.get(focus.index()) == item, animate);
        }
        FocusDim.applyAlpha(weatherItem, FocusDim.Role.TOP_BAR, focus.zone() == FocusModel.Zone.WEATHER, animate);
        applyAllInRow(favoritesRow, FocusModel.Zone.FAVORITES, animate);
        applyAllInRow(watchRow, FocusModel.Zone.WATCH_NEXT, animate);
        applyAllInRow(mixedRow, FocusModel.Zone.MIXED, animate);
    }

    private void applyAllInRow(RecyclerView row, FocusModel.Zone zone, boolean animate) {
        int selected = focus.zone() == zone ? focus.index() : -1;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            boolean focused = row.getChildAdapterPosition(child) == selected;
            if (zone == FocusModel.Zone.FAVORITES) {
                ((DimImageView) child.findViewById(R.id.tile_image)).setFocused(focused, animate);
            } else {
                Tiles.setCardSelected(child, focused, animate);
            }
        }
    }

    /**
     * Glass target (page coordinates) of an item in a horizontal feed row, where it will be once the row has scrolled
     * to its target: the row is retargeted so the item is fully visible, and the glass goes to the end position of the
     * item right away. Works for items that are not laid out yet (their place follows from the card widths).
     */
    private boolean locateInRow(RecyclerView row, RowScroller scroller, FeedAdapter adapter, int pos, RectF out) {
        LinearLayoutManager layout = (LinearLayoutManager) row.getLayoutManager();
        int first = layout.findFirstVisibleItemPosition();
        View ref = first < 0 ? null : layout.findViewByPosition(first);
        if (ref == null) {
            row.scrollToPosition(pos);
            applySelectionAfterLayout();
            return false;
        }
        View item = layout.findViewByPosition(pos);
        int left;
        int width;
        if (item != null) {
            left = item.getLeft();
            width = item.getWidth();
            if (!Tiles.locate(item.findViewById(R.id.card_image), page, out)) return false;
        } else {
            left = ref.getLeft();
            int step = adapter.gapPx();
            if (pos > first) for (int i = first; i < pos; i++) left += adapter.cardWidthPx(i) + step;
            else for (int i = pos; i < first; i++) left -= adapter.cardWidthPx(i) + step;
            width = adapter.cardWidthPx(pos);
            if (!Tiles.locate(ref.findViewById(R.id.card_image), page, out)) return false;
            out.offset(left - ref.getLeft(), 0);
            out.right = out.left + width;
        }
        double pending = scroller.pending();
        double endLeft = left - pending;
        double endRight = endLeft + width;
        double dx = 0;
        if (endLeft < row.getPaddingLeft()) dx = endLeft - row.getPaddingLeft();
        else if (endRight > row.getWidth() - row.getPaddingRight()) dx = endRight - (row.getWidth() - row.getPaddingRight());
        scroller.retargetBy(dx);
        out.offset((float) -(pending + dx), 0);
        return true;
    }

    /**
     * Glass target (page coordinates) of a favorites-row tile at its place after the row has scrolled to the position
     * {@link FavoritesRowLayout#scrollFor} wants for it (the tile fully visible with the right margin kept, the favorites
     * aligned again at the apps button). The row is retargeted, not restarted; the tile's place follows from the fixed
     * tile geometry, so it works for tiles that are not laid out yet.
     */
    private boolean locateFavorite(int pos, RectF out) {
        if (favAdapter.getItemCount() == 0) return false;
        LinearLayoutManager layout = (LinearLayoutManager) favoritesRow.getLayoutManager();
        int first = layout.findFirstVisibleItemPosition();
        View ref = first < 0 ? null : layout.findViewByPosition(first);
        if (ref == null || !Tiles.locate(ref.findViewById(R.id.tile_image), page, out)) return false;
        int actual = favoritesScrollX();
        double pending = favoritesScroller.pending();
        int target = (int) Math.round(actual + pending);
        double dx = favAdapter.scrollFor(pos, target) - target;
        favoritesScroller.retargetBy(dx);
        out.offset((float) (favAdapter.leftOf(pos) - favAdapter.leftOf(first) - pending - dx), 0);
        return true;
    }

    /** How far the favorites row is scrolled from its start, from its first visible tile. */
    private int favoritesScrollX() {
        LinearLayoutManager layout = (LinearLayoutManager) favoritesRow.getLayoutManager();
        int first = layout.findFirstVisibleItemPosition();
        if (first < 0) return 0;
        return favAdapter.leftOf(first) - (layout.findViewByPosition(first).getLeft() - favoritesRow.getPaddingLeft());
    }

    // ---------------------------------------------------------------- keys

    @Override
    public void onUserInteraction() {
        idle.onInteraction();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (idle.consumes(e)) return true;
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) onBack();
            return true;
        }
        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        if (center) {
            switch (centerKey.onKey(down, e.getRepeatCount())) {
                case SHORT:
                    activate();
                    break;
                case LONG:
                    onLongPress();
                    break;
                default:
                    break;
            }
            return true;
        }
        FocusModel.Dir dir = directionOf(k);
        if (dir == null) return super.dispatchKeyEvent(e);
        if (!down) return true;
        boolean vertical = dir == FocusModel.Dir.UP || dir == FocusModel.Dir.DOWN;
        if (e.getRepeatCount() > 0 && vertical) return true; // one row per press
        onDirection(dir);
        return true;
    }

    private static FocusModel.Dir directionOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return FocusModel.Dir.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return FocusModel.Dir.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return FocusModel.Dir.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return FocusModel.Dir.RIGHT;
            default: return null;
        }
    }

    private void onDirection(FocusModel.Dir dir) {
        switch (mode) {
            case MENU:
                if (dir == FocusModel.Dir.UP) menu.moveSelection(-1);
                else if (dir == FocusModel.Dir.DOWN) menu.moveSelection(1);
                break;
            case MOVE:
                if (dir == FocusModel.Dir.LEFT) moveFavorite(-1);
                else if (dir == FocusModel.Dir.RIGHT) moveFavorite(1);
                break;
            default:
                if (focus.move(dir)) scheduleFocusVisuals(); // a press clamped at the row end changes nothing
                break;
        }
    }

    private void onBack() {
        if (mode != Mode.NAVIGATE) endMenuAndMove();
    }

    private void endMenuAndMove() {
        mode = Mode.NAVIGATE;
        menu.hide();
        updateInfo();
        applySelection(false);
    }

    private void activate() {
        switch (mode) {
            case MENU:
                activateMenuItem();
                return;
            case MOVE:
                endMenuAndMove();
                return;
            default:
                break;
        }
        switch (focus.zone()) {
            case WEATHER:
                if (zonePrefs.location() == null) LauncherSettingsActivity.open(this, true);
                else weather.refreshNow();
                break;
            case TOP: {
                TopBarItem item = visibleTopItems().get(focus.index());
                if (item == vpnItem) onVpnPressed();
                else if (item == wifiItem) startSystem(Settings.ACTION_WIFI_SETTINGS);
                else if (item == launcherSettingsItem) LauncherSettingsActivity.open(this, false);
                else startSystem(Settings.ACTION_SETTINGS);
                break;
            }
            case FAVORITES:
                AppEntry entry = favAdapter.entry(focus.index());
                if (entry != null) launch(entry);
                else startActivity(new Intent(this, AllAppsActivity.class));
                break;
            case WATCH_NEXT:
                openProgram(watchAdapter.item(focus.index()));
                break;
            default:
                openProgram(mixedAdapter.item(focus.index()));
        }
    }

    private void onLongPress() {
        if (mode != Mode.NAVIGATE) return;
        RectF anchor = new RectF();
        String[] labels;
        FavoritesRowState.Kind kind = focus.zone() == FocusModel.Zone.FAVORITES ? favAdapter.kind(focus.index()) : null;
        if (kind != null && kind != FavoritesRowState.Kind.APPS_BUTTON) {
            RecyclerView.ViewHolder vh = favoritesRow.findViewHolderForAdapterPosition(focus.index());
            if (vh == null || !Tiles.locate(vh.itemView.findViewById(R.id.tile_image), root, anchor)) return;
            labels = kind == FavoritesRowState.Kind.FAVORITE
                    ? new String[]{getString(R.string.menu_move), getString(R.string.menu_remove), getString(R.string.menu_manage)}
                    : new String[]{getString(R.string.menu_add), getString(R.string.menu_manage)};
        } else {
            return;
        }
        mode = Mode.MENU;
        menu.show(labels, anchor);
    }

    private void activateMenuItem() {
        int choice = menu.selected();
        menu.hide();
        boolean favoriteTile = focus.zone() == FocusModel.Zone.FAVORITES
                && favAdapter.kind(focus.index()) == FavoritesRowState.Kind.FAVORITE;
        if (!favoriteTile) { // continuation tile: «Добавить в главные» / «Управление приложением»
            mode = Mode.NAVIGATE;
            AppEntry target = favAdapter.entry(focus.index());
            if (choice == 1) {
                AppInfoScreen.open(this, target.packageName);
                return;
            }
            favorites.add(target.packageName);
            rebuildFavorites(); // the app leaves the continuation; its dot shows on the new tile
            focus.setIndex(favorites.list().indexOf(target.packageName));
            onFocusChanged(true);
            return;
        }
        if (choice == 2) {
            mode = Mode.NAVIGATE;
            AppInfoScreen.open(this, favEntries.get(focus.index()).packageName);
            return;
        }
        if (choice == 0) {
            mode = Mode.MOVE;
            updateInfo();
            applySelection(false);
            return;
        }
        mode = Mode.NAVIGATE;
        favorites.remove(favEntries.get(focus.index()).packageName);
        rebuildFavorites();
    }

    private void moveFavorite(int delta) {
        int newIndex = favorites.move(favEntries.get(focus.index()).packageName, delta);
        rebuildFavorites();
        focus.setIndex(newIndex);
        onFocusChanged(true);
    }

    private void launch(AppEntry e) {
        try {
            startActivity(e.launchIntent);
        } catch (RuntimeException ex) {
            Log.w(TAG, "launch failed: " + e.packageName, ex);
        }
    }

    private void openProgram(FeedItem item) {
        try {
            Intent intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            intent.setSelector(null);
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (URISyntaxException | RuntimeException ex) {
            Log.w(TAG, "cannot open " + item.packageName + ": " + item.intentUri, ex);
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void startSystem(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            Log.w(TAG, "system screen unavailable: " + action, ex);
        }
    }

    // ---------------------------------------------------------------- VPN item

    /** Re-reads the VPN settings: shown only when enabled and a VPN app is installed. */
    private void updateVpnItemVisibility() {
        LauncherSettings settings = settingsStore.load();
        vpnPackage = settings.vpnButtonVisible
                ? VpnApps.choose(settings.vpnPackage, VpnAppsLoader.installed(getPackageManager()),
                        getString(R.string.vpn_default_package))
                : "";
        boolean show = !vpnPackage.isEmpty();
        int vis = show ? View.VISIBLE : View.GONE;
        if (vpnItem.getVisibility() != vis) {
            vpnItem.setVisibility(vis);
            updateCounts(); // also resets appliedSelection: top-bar indices changed meaning
            applySelectionAfterLayout();
        }
    }

    private void onVpnState(boolean on) {
        vpnOn = on;
        if (vpnSwitching && on == vpnTargetOn) {
            vpnSwitching = false;
            main.removeCallbacks(vpnTimeout);
        }
        renderVpn();
    }

    /** OFF: open the VPN app so the user connects there. ON: fire the silent-off shortcut and wait for the network callback. */
    private void onVpnPressed() {
        if (vpnSwitching || vpnPackage.isEmpty()) return;
        ComponentName toggleOff = toggleOffComponent();
        if (VpnApps.decide(vpnOn, toggleOff != null) == VpnApps.Action.TOGGLE_OFF) switchVpnOff(toggleOff);
        else openVpnApp();
    }

    /** The silent-off component configured for the selected VPN app, or null. */
    private ComponentName toggleOffComponent() {
        Resources res = getResources();
        String flat = VpnApps.toggleOffComponent(vpnPackage, res.getStringArray(R.array.vpn_toggle_off_packages),
                res.getStringArray(R.array.vpn_toggle_off_components));
        return flat.isEmpty() ? null : ComponentName.unflattenFromString(flat);
    }

    /** Leanback launch intent, else the plain launcher intent, of the selected VPN app. */
    private void openVpnApp() {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(vpnPackage);
        if (intent == null) intent = pm.getLaunchIntentForPackage(vpnPackage);
        if (intent == null) {
            showVpnError();
            return;
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void switchVpnOff(ComponentName toggleOff) {
        vpnTargetOn = false;
        vpnSwitching = true;
        vpnError = false;
        main.removeCallbacks(vpnErrorClear);
        try {
            startActivity(new Intent().setComponent(toggleOff).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "VPN shortcut failed", e);
            vpnSwitching = false;
            showVpnError();
            return;
        }
        main.postDelayed(vpnTimeout, VPN_TIMEOUT_MS);
        renderVpn();
    }

    private void onVpnTimeout() {
        vpnSwitching = false;
        showVpnError();
    }

    private void showVpnError() {
        vpnError = true;
        main.removeCallbacks(vpnErrorClear);
        main.postDelayed(vpnErrorClear, VPN_ERROR_MS);
        renderVpn();
    }

    private void renderVpn() {
        int color;
        String label;
        if (vpnError) {
            color = DOT_ERROR;
            label = getString(R.string.bar_vpn_error);
        } else if (vpnSwitching) {
            color = DOT_BUSY;
            label = getString(R.string.bar_vpn_switching);
        } else {
            color = vpnOn ? DOT_ON : DOT_OFF;
            label = getString(R.string.bar_vpn);
        }
        vpnItem.setDotColor(color);
        vpnItem.setLabel(label);
        if (vpnSwitching) startVpnPulse();
        else stopVpnPulse();
        applySelectionAfterLayout();
    }

    private void startVpnPulse() {
        if (vpnPulse != null) return;
        vpnPulse = ObjectAnimator.ofFloat(vpnItem.dot(), View.ALPHA, 1f, 0.25f);
        vpnPulse.setDuration(600);
        vpnPulse.setRepeatCount(ObjectAnimator.INFINITE);
        vpnPulse.setRepeatMode(ObjectAnimator.REVERSE);
        vpnPulse.start();
    }

    private void stopVpnPulse() {
        if (vpnPulse == null) return;
        vpnPulse.cancel();
        vpnPulse = null;
        vpnItem.dot().setAlpha(1f);
    }

    // ---------------------------------------------------------------- date

    /** Clock, date and screensaver clock follow the city's timezone (the device's while no city is set). */
    private void applyZone() {
        TimeZone zone = zonePrefs.zone();
        clock.setTimeZone(zone.getID());
        idle.screensaver().setTimeZone(zone);
        dateFormat.setTimeZone(zone);
        date.setText(dateFormat.format(new Date()));
    }
}
