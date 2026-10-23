package com.alexmak.atvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fullscreen list of every launchable app in titled sections, most launched (last 30 days) first inside a section.
 * Paints at once from {@link AppCache}, then refreshes in the background; long-press OK manages favorites.
 */
public final class AllAppsActivity extends Activity {
    private static final String TAG = "AtvAllApps";
    private static final int COLUMNS = 5;
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    /** One adapter row: a section header (app == null) or an app. */
    private static final class Row {
        final String title;
        final AppEntry app;

        Row(String title, AppEntry app) {
            this.title = title;
            this.app = app;
        }

        String id() {
            return app == null ? "H:" + title : "A:" + app.packageName;
        }
    }

    private View root;
    private RecyclerView grid;
    private GlassView glass;
    private GlassMenu menu;
    private BackdropController backdrop;
    /** The backdrop was dropped by a memory trim and must be asked for again in onStart. */
    private boolean backdropTrimmed;
    private float density;

    private LinearLayout headerBar;
    private List<String> headerPackages;
    private String settingsPackage;
    private Set<String> headerPackageSet;
    private List<AppEntry> headerApps = new ArrayList<>();
    private List<String> groupTitles;
    private final List<java.util.Set<String>> groupPackages = new ArrayList<>();
    private List<AppGrouping.Section<AppEntry>> sections = new ArrayList<>();
    private List<Row> rows = new ArrayList<>();
    private final SectionedGridFocus focus = new SectionedGridFocus(COLUMNS);
    private FavoritesStore favorites;
    private final CenterKey centerKey = new CenterKey();
    private boolean menuOpen;
    private boolean selectionPending;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Adapter adapter;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_apps);
        density = getResources().getDisplayMetrics().density;
        root = findViewById(R.id.root);
        grid = findViewById(R.id.grid);
        glass = findViewById(R.id.glass);
        headerBar = findViewById(R.id.header_apps);
        menu = findViewById(R.id.menu);
        AppBackdropImage appImages = new AppBackdropImage(getPackageManager());
        backdrop = new BackdropController(findViewById(R.id.backdrop), main, worker,
                key -> appImages.load(BackdropKeys.packageOf(key)));
        favorites = FavoritesPrefs.open(this);
        loadGroupConfig();

        adapter = new Adapter();
        GridLayoutManager lm = new GridLayoutManager(this, COLUMNS);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return rows.get(position).app == null ? COLUMNS : 1;
            }
        });
        grid.setLayoutManager(lm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) applySelection(false);
            }
        });

        AppLoader.Result cached = AppCache.apps();
        if (cached != null) show(cached.apps, AppCache.launches());

        worker.execute(() -> {
            AppLoader.Result fresh = AppCache.loader(this).load();
            Map<String, Long> launches = LaunchStats.last30Days(this);
            AppCache.setApps(fresh);
            AppCache.setLaunches(launches);
            main.post(() -> {
                if (!isDestroyed()) show(fresh.apps, launches);
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (backdropTrimmed) {
            backdropTrimmed = false;
            wantBackdrop();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!TrimPolicy.evictsCaches(level)) return;
        backdrop.clear();
        backdropTrimmed = true;
        ShadowView.evictCache();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        backdrop.release();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadGroupConfig() {
        Resources res = getResources();
        groupTitles = Arrays.asList(res.getStringArray(R.array.app_group_titles));
        groupPackages.add(new HashSet<>(Arrays.asList(res.getStringArray(R.array.app_group_cinemas))));
        groupPackages.add(new HashSet<>(Arrays.asList(res.getStringArray(R.array.app_group_tools))));
        headerPackages = Arrays.asList(res.getStringArray(R.array.app_header_packages));
        headerPackageSet = new HashSet<>(headerPackages);
        settingsPackage = res.getString(R.string.settings_package);
    }

    /** Regroups the apps and applies the difference to the list, keeping focus on the same app. */
    private void show(List<AppEntry> apps, Map<String, Long> launches) {
        AppEntry focused = focusedApp();
        showHeader(AppGrouping.pick(apps, a -> a.packageName, headerPackages));
        apps = AppGrouping.exclude(apps, a -> a.packageName, headerPackageSet);
        sections = AppGrouping.group(apps, a -> a.packageName, a -> a.label, launches, groupTitles, groupPackages);
        List<Row> newRows = new ArrayList<>();
        int[] sizes = new int[sections.size()];
        for (int s = 0; s < sections.size(); s++) {
            sizes[s] = sections.get(s).items.size();
            newRows.add(new Row(sections.get(s).title, null));
            for (AppEntry e : sections.get(s).items) newRows.add(new Row(null, e));
        }
        focus.setSections(sizes);
        if (focused != null) selectApp(focused.packageName);

        final List<Row> old = rows;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return newRows.size();
            }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o).id().equals(newRows.get(n).id());
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                Row a = old.get(o);
                Row b = newRows.get(n);
                return a.app == null || (a.app.label.equals(b.app.label) && a.app.tile == b.app.tile);
            }
        });
        rows = newRows;
        diff.dispatchUpdatesTo(adapter);
        applySelectionAfterLayout();
    }

    /** Rebuilds the header icon buttons when the set of installed header apps changed. */
    private void showHeader(List<AppEntry> newHeader) {
        List<String> oldPkgs = new ArrayList<>();
        for (AppEntry e : headerApps) oldPkgs.add(e.packageName);
        List<String> newPkgs = new ArrayList<>();
        for (AppEntry e : newHeader) newPkgs.add(e.packageName);
        if (oldPkgs.equals(newPkgs)) return;
        headerApps = newHeader;
        headerBar.removeAllViews();
        int size = Math.round(44 * density);
        int pad = Math.round(6 * density);
        for (AppEntry e : headerApps) {
            View icon;
            if (e.packageName.equals(settingsPackage)) {
                // the home top-bar gear: plain white glyph, centred in the store icons' footprint
                TopBarItem gear = new TopBarItem(this, false, true, false);
                gear.setIcon(R.drawable.ic_settings);
                gear.setGravity(Gravity.CENTER);
                icon = gear;
            } else {
                DimImageView store = new DimImageView(this);
                store.setImageBitmap(e.icon);
                store.setPadding(pad, pad, pad, pad);
                store.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                icon = store;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (headerBar.getChildCount() > 0) lp.setMarginStart(Math.round(12 * density));
            headerBar.addView(icon, lp);
        }
        focus.setHeaderCount(headerApps.size());
    }

    /** The page backdrop follows the focused app (header icon or grid tile). */
    private void wantBackdrop() {
        AppEntry focusedEntry = focus.inHeader() ? headerApps.get(focus.headerIndex()) : focusedApp();
        if (focusedEntry != null) backdrop.want(BackdropKeys.app(focusedEntry.packageName));
    }

    private AppEntry focusedApp() {
        if (sections.isEmpty() || focus.inHeader()) return null;
        return sections.get(focus.section()).items.get(focus.index());
    }

    private void selectApp(String pkg) {
        for (int s = 0; s < sections.size(); s++) {
            List<AppEntry> items = sections.get(s).items;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).packageName.equals(pkg)) {
                    focus.select(s, i);
                    return;
                }
            }
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final class AppHolder extends RecyclerView.ViewHolder {
            final DimImageView image;
            final ShadowView shadow;
            final TextView name;

            AppHolder(View v) {
                super(v);
                image = v.findViewById(R.id.tile_image);
                shadow = v.findViewById(R.id.shadow);
                name = v.findViewById(R.id.tile_name);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).app == null ? TYPE_HEADER : TYPE_APP;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_header, parent, false)) { };
            }
            AppHolder h = new AppHolder(inflater.inflate(R.layout.item_app, parent, false));
            h.shadow.setRadius(14 * density);
            return h;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.app == null) {
                ((TextView) holder.itemView).setText(row.title);
                return;
            }
            AppHolder h = (AppHolder) holder;
            Tiles.bindImage(h.image, row.app.tile, 14 * density);
            h.name.setText(row.app.label);
            select(h, position == focus.flatPosition(), false);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private void select(Adapter.AppHolder h, boolean selected, boolean animate) {
        Tiles.setSelected(h.image, h.shadow, selected, animate);
        FocusDim.applyAlpha(h.name, FocusDim.Role.TEXT, selected, animate);
    }

    private void applySelectionAfterLayout() {
        if (selectionPending) return;
        selectionPending = true;
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                selectionPending = false;
                applySelection(false);
                return true;
            }
        });
    }

    private void applySelection(boolean animate) {
        for (int i = 0; i < headerBar.getChildCount(); i++) {
            View icon = headerBar.getChildAt(i);
            boolean focused = focus.inHeader() && i == focus.headerIndex();
            if (icon instanceof DimImageView) ((DimImageView) icon).setFocused(focused, animate);
            else FocusDim.applyAlpha(icon, FocusDim.Role.TOP_BAR, focused, animate);
        }
        wantBackdrop();
        if (focus.inHeader()) {
            applyHeaderSelection(animate);
            return;
        }
        AppEntry focused = focusedApp();
        if (focused == null) return;
        int pos = focus.flatPosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            RecyclerView.ViewHolder h = grid.getChildViewHolder(grid.getChildAt(i));
            if (h instanceof Adapter.AppHolder) {
                select((Adapter.AppHolder) h, h.getBindingAdapterPosition() == pos, animate);
            }
        }
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (!(vh instanceof Adapter.AppHolder)) {
            grid.scrollToPosition(pos);
            applySelectionAfterLayout();
            return;
        }
        RectF target = new RectF();
        if (!Tiles.locate(((Adapter.AppHolder) vh).image, root, target)) return;
        // in the first row of a section keep its header on screen too
        int top = vh.itemView.getTop();
        if (focus.index() < COLUMNS) {
            RecyclerView.ViewHolder header = grid.findViewHolderForAdapterPosition(pos - focus.index() - 1);
            if (header != null) top = Math.min(top, header.itemView.getTop());
        }
        int dy = 0;
        if (top < 0) dy = top;
        else if (vh.itemView.getBottom() > grid.getHeight()) dy = vh.itemView.getBottom() - grid.getHeight();
        target.offset(0, -dy);
        float radius = Tiles.grow(target, density, 14);
        if (dy != 0) grid.smoothScrollBy(0, dy, new DecelerateInterpolator(), 250);
        glass.moveTo(target, radius, animate);
    }

    /** Glass on the focused header button; the grid shows no selection meanwhile. */
    private void applyHeaderSelection(boolean animate) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            RecyclerView.ViewHolder h = grid.getChildViewHolder(grid.getChildAt(i));
            if (h instanceof Adapter.AppHolder) select((Adapter.AppHolder) h, false, animate);
        }
        RectF target = new RectF();
        if (!Tiles.locate(headerBar.getChildAt(focus.headerIndex()), root, target)) return;
        float radius = Tiles.surround(target, density, 10);
        glass.moveTo(target, radius, animate);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) onBack();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            switch (centerKey.onKey(down, e.getRepeatCount())) {
                case SHORT:
                    activate();
                    break;
                case LONG:
                    openMenu();
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
        if (e.getRepeatCount() > 0 && vertical) return true;
        if (!menuOpen && focus.move(dir)) applySelection(true);
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

    private void onBack() {
        if (menuOpen) closeMenu();
        else finish();
    }

    private void activate() {
        if (menuOpen) {
            applyMenuChoice();
            return;
        }
        AppEntry app = focus.inHeader() ? headerApps.get(focus.headerIndex()) : focusedApp();
        if (app == null) return;
        try {
            startActivity(app.launchIntent);
        } catch (RuntimeException ex) {
            Log.w(TAG, "launch failed: " + app.packageName, ex);
        }
    }

    private void openMenu() {
        AppEntry app = focusedApp();
        if (menuOpen || app == null) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(focus.flatPosition());
        RectF anchor = new RectF();
        if (!(vh instanceof Adapter.AppHolder)
                || !Tiles.locate(((Adapter.AppHolder) vh).image, root, anchor)) {
            return;
        }
        String label = favorites.contains(app.packageName)
                ? getString(R.string.menu_remove) : getString(R.string.menu_add);
        menuOpen = true;
        menu.show(new String[]{label, getString(R.string.menu_manage)}, anchor);
    }

    private void applyMenuChoice() {
        String pkg = focusedApp().packageName;
        int choice = menu.selected();
        closeMenu();
        if (choice == 1) {
            AppInfoScreen.open(this, pkg);
        } else if (favorites.contains(pkg)) {
            favorites.remove(pkg);
        } else {
            favorites.add(pkg);
        }
    }

    private void closeMenu() {
        menuOpen = false;
        menu.hide();
    }
}
