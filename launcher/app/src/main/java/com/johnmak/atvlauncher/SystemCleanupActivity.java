package com.johnmak.atvlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * System cleanup: an automatic run of the device profile plus one list of all apps sorted by size (toggle, remove, roll back
 * updates) and the block of removed system apps. D-pad list in the settings look; every action needs OK twice.
 * Commands run on a worker through the loopback adb; sizes, memory and CPU arrive after the first render.
 */
public final class SystemCleanupActivity extends Activity {
    private static final String TAG = "AtvCleanup";
    private static final String KEY_AUTO = "auto";
    private static final String KEY_RESTORE = "restore:";

    private enum Zone { AUTO, LIST, REMOVED }

    /** Views of one app row; created once per package and rebound when the data changes. */
    private final class AppRow {
        final String pkg;
        final LinearLayout view;
        final ImageView check;
        final ImageView icon;
        final TextView title;
        final TextView star;
        final TextView desc;
        final TextView stats;
        final TextView note;
        final ImageView trash;
        final ImageView rollback;

        AppRow(String pkg) {
            this.pkg = pkg;
            view = rowContainer();
            check = newControl();
            ((LinearLayout.LayoutParams) check.getLayoutParams()).setMarginStart(0);
            ((LinearLayout.LayoutParams) check.getLayoutParams()).setMarginEnd(dp(14));
            view.addView(check);
            icon = appIcon(pkg);
            view.addView(icon, iconParams());
            LinearLayout col = new LinearLayout(SystemCleanupActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout titleLine = new LinearLayout(SystemCleanupActivity.this);
            titleLine.setGravity(Gravity.CENTER_VERTICAL);
            title = text("", R.color.text_primary, R.font.outfit_regular, 20);
            titleLine.addView(title);
            star = text("★", R.color.warning_yellow, R.font.outfit_regular, 14);
            star.setPadding(dp(8), 0, 0, 0);
            titleLine.addView(star);
            col.addView(titleLine);
            desc = twoLines("");
            col.addView(desc);
            stats = text("", R.color.text_secondary, R.font.outfit_light, 13);
            col.addView(stats);
            view.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            note = text("", R.color.text_secondary, R.font.outfit_light, 16);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.setMarginStart(dp(12));
            view.addView(note, nlp);
            trash = newControl();
            trash.setImageResource(R.drawable.ic_trash);
            view.addView(trash);
            rollback = newControl();
            rollback.setImageResource(R.drawable.ic_rollback);
            view.addView(rollback);
            view.setAlpha(FocusDim.Role.TEXT.unfocused);
        }

        void bind(CleanupList.Row r) {
            int color = r.locked || !r.enabled ? R.color.text_secondary : R.color.text_primary;
            title.setText(r.label);
            title.setTextColor(getColor(color));
            star.setVisibility(r.recommended != null ? View.VISIBLE : View.GONE);
            boolean hasDesc = r.description != null && !r.description.isEmpty();
            desc.setVisibility(hasDesc ? View.VISIBLE : View.GONE);
            desc.setText(hasDesc ? r.description : "");
            stats.setText(getString(R.string.cleanup_stats_line, StatsFormat.bytes(r.stats.sizeBytes),
                    StatsFormat.kilobytes(r.stats.pssKb), StatsFormat.millis(r.stats.cpuMillis)));
            check.setImageResource(r.locked ? R.drawable.ic_lock : r.enabled ? R.drawable.ic_checkbox_on : R.drawable.ic_checkbox_off);
            check.setAlpha(r.locked ? 0.6f : 1f);
            icon.setAlpha(r.locked || !r.enabled ? 0.5f : 1f);
            trash.setVisibility(r.actions.contains(CleanupList.Col.TRASH) ? View.VISIBLE : View.INVISIBLE);
            rollback.setVisibility(r.actions.contains(CleanupList.Col.ROLLBACK) ? View.VISIBLE : View.INVISIBLE);
        }

        View control(CleanupList.Col col) {
            switch (col) {
                case TOGGLE: return check;
                case TRASH: return trash;
                default: return rollback;
            }
        }
    }

    private final class RemovedRow {
        final String pkg;
        final LinearLayout view;
        final TextView note;

        RemovedRow(CleanupList.Removed r) {
            pkg = r.pkg;
            view = rowContainer();
            view.addView(appIcon(pkg), iconParams());
            LinearLayout col = new LinearLayout(SystemCleanupActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text((r.ours ? "★ " : "") + r.label, R.color.text_primary, R.font.outfit_regular, 20));
            col.addView(twoLines(r.description == null ? "" : r.description));
            view.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            note = text("", R.color.text_secondary, R.font.outfit_light, 16);
            view.addView(note);
            view.setAlpha(FocusDim.Role.TEXT.unfocused);
        }
    }

    private View root;
    private ScrollView scroll;
    private LinearLayout list;
    private GlassView glass;
    private TextView statusView;
    private float density;

    private LinearLayout autoView;
    private TextView autoValue;
    private View appsHeader;
    private View removedHeader;
    private final Map<String, AppRow> appRows = new HashMap<>();
    private final List<AppRow> shownApps = new ArrayList<>();
    private final List<RemovedRow> shownRemoved = new ArrayList<>();
    private final Set<String> iconRequested = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();
    private final CleanupConfirm confirm = new CleanupConfirm();
    private final CenterKey centerKey = new CenterKey();
    private CleanupRowFocus focus;
    private Zone zone = Zone.AUTO;
    private int removedIndex;
    private View dimmed;
    private boolean layoutPending;
    private boolean busy;
    private String progress = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService statsWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService icons = Executors.newSingleThreadExecutor();

    private RequiredRights.Probe rights;
    private LoopbackAdbSession session;
    private CleanupProfile profile;
    private AppDescriptions descriptions;
    private CleanupLog log = CleanupLog.empty();
    private CleanupLog.Storage logStorage;
    private CleanupInventory.Snapshot snap;
    private CleanupList.Result result;
    private Map<String, Long> sizes = new HashMap<>();
    private Map<String, Long> pss = new HashMap<>();
    private Map<String, Long> cpu = new HashMap<>();
    private boolean usageDenied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_system_cleanup);
        density = getResources().getDisplayMetrics().density;
        root = findViewById(R.id.root);
        scroll = findViewById(R.id.scroll);
        list = findViewById(R.id.list);
        glass = findViewById(R.id.glass);
        statusView = findViewById(R.id.status);
        rights = RequiredRights.android(this);
        session = LoopbackAdbSession.shared(this);
        logStorage = new PrefsCleanupLogStorage(this);
        FetchResult<CleanupLog> loaded = CleanupLog.loadFrom(logStorage);
        if (loaded.isOk()) {
            log = loaded.value();
        } else {
            Log.w(TAG, "cleanup log: " + loaded.error());
            statusView.setText(R.string.cleanup_log_broken);
        }
        if (statusView.getText().length() == 0) statusView.setText(R.string.cleanup_loading);
        worker.execute(() -> {
            CleanupProfile p = CleanupSections.selectProfile(CleanupInventory.profiles(this), Build.MODEL, Build.PRODUCT);
            FetchResult<AppDescriptions> d = AppDescriptions.load(this);
            if (!d.isOk()) Log.w(TAG, "descriptions: " + d.error());
            main.post(() -> {
                profile = p;
                descriptions = d.isOk() ? d.value() : null;
            });
            CleanupInventory.Snapshot s = reload();
            loadStats(s);
        });
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        statsWorker.shutdownNow();
        icons.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- data

    /** Reads the inventory on the worker and rebuilds the list from it. */
    private CleanupInventory.Snapshot reload() {
        CleanupInventory.Snapshot s = CleanupInventory.load(this, rights);
        main.post(() -> {
            if (isDestroyed()) return;
            snap = s;
            rebuild();
        });
        return s;
    }

    /** Sizes first (fast), then memory and CPU (a slow meminfo call); each arrival re-sorts and re-renders. */
    private void loadStats(CleanupInventory.Snapshot s) {
        statsWorker.execute(() -> {
            CleanupInventory.Sizes sz = CleanupInventory.sizes(this, s.apps);
            main.post(() -> {
                if (isDestroyed()) return;
                usageDenied = sz.denied;
                sizes = sz.bytes;
                rebuild();
            });
            Map<String, Long> mem = parsed(session.exec(ShellCommand.Stats.meminfoCompact()), true);
            main.post(() -> {
                if (isDestroyed()) return;
                pss = mem;
                rebuild();
            });
            Map<String, Long> times = parsed(session.exec(ShellCommand.Stats.cpuTimes()), false);
            main.post(() -> {
                if (isDestroyed()) return;
                cpu = times;
                rebuild();
            });
        });
    }

    private static Map<String, Long> parsed(FetchResult<String> raw, boolean meminfo) {
        if (!raw.isOk()) {
            Log.w(TAG, "stats command: " + raw.error());
            return new HashMap<>();
        }
        FetchResult<Map<String, Long>> p = meminfo ? MeminfoParser.parse(raw.value()) : ProcessTimesParser.parse(raw.value());
        if (!p.isOk()) {
            Log.w(TAG, "stats parse: " + p.error());
            return new HashMap<>();
        }
        return p.value();
    }

    private Map<String, CleanupList.Stats> statsByPackage() {
        Map<String, CleanupList.Stats> out = new HashMap<>();
        Set<String> all = new HashSet<>(sizes.keySet());
        all.addAll(pss.keySet());
        all.addAll(cpu.keySet());
        for (String pkg : all) out.put(pkg, new CleanupList.Stats(sizes.get(pkg), pss.get(pkg), cpu.get(pkg)));
        return out;
    }

    private synchronized CleanupLog currentLog() { return log; }

    private synchronized void record(CleanupSections.Op op, String pkg, long now) {
        CleanupProfile.Action a = op.logged();
        log = a != null ? log.record(pkg, a, now) : log.forget(pkg);
        log.saveTo(logStorage);
    }

    /** Runs one op; returns null on success or the text to show. */
    private String execute(CleanupSections.Op op, String pkg) {
        FetchResult<PackageName> name = PackageName.parse(pkg);
        if (!name.isOk()) return getString(R.string.cleanup_failed, "?");
        FetchResult<String> r = session.exec(op.command(name.value()));
        if (r.isOk()) {
            record(op, pkg, System.currentTimeMillis());
            return null;
        }
        ShellError kind = ShellError.of(r);
        Log.w(TAG, op + " " + pkg + ": " + r.error());
        return kind == ShellError.UNAUTHORIZED ? getString(R.string.settings_rights_unauthorized)
                : getString(R.string.cleanup_failed, kind == null ? "?" : kind.name());
    }

    private static boolean connectionLevel(ShellError kind) {
        return kind == ShellError.UNAUTHORIZED || kind == ShellError.UNREACHABLE;
    }

    // ---------------------------------------------------------------- actions

    private void runAuto() {
        if (busy || result == null || result.autoSteps.isEmpty()) return;
        if (!confirm.press(KEY_AUTO)) {
            refreshNotes();
            return;
        }
        List<CleanupPlanner.Step> steps = result.autoSteps;
        busy = true;
        errors.remove(KEY_AUTO);
        progress = getString(R.string.cleanup_progress, 0, steps.size());
        refreshNotes();
        worker.execute(() -> {
            int done = 0;
            int failed = 0;
            String stop = null;
            for (CleanupPlanner.Step s : steps) {
                if (destroyed || Thread.currentThread().isInterrupted()) return; // screen closed: no further writes
                CleanupSections.Op op = CleanupSections.Op.of(s.action);
                FetchResult<PackageName> name = PackageName.parse(s.pkg);
                if (!name.isOk()) {
                    failed++;
                } else {
                    FetchResult<String> r = session.exec(op.command(name.value()));
                    if (r.isOk()) {
                        record(op, s.pkg, System.currentTimeMillis());
                    } else {
                        ShellError kind = ShellError.of(r);
                        Log.w(TAG, op + " " + s.pkg + ": " + r.error());
                        if (connectionLevel(kind)) {
                            stop = kind == ShellError.UNAUTHORIZED ? getString(R.string.settings_rights_unauthorized)
                                    : getString(R.string.cleanup_failed, kind.name());
                            break;
                        }
                        failed++;
                    }
                }
                done++;
                String p = getString(R.string.cleanup_progress, done, steps.size());
                main.post(() -> {
                    progress = p;
                    refreshNotes();
                });
            }
            String summary = stop != null ? stop : getString(R.string.cleanup_done, done - failed, failed);
            main.post(() -> errors.put(KEY_AUTO, summary));
            finishRun();
        });
    }

    /** Runs {@code op} on {@code pkg} after the second press; {@code key} is the confirmation key already satisfied. */
    private void runOp(String key, CleanupSections.Op op, String pkg) {
        busy = true;
        errors.remove(key);
        statusView.setText(R.string.cleanup_working);
        refreshNotes();
        worker.execute(() -> {
            String err = execute(op, pkg);
            if (err != null) main.post(() -> errors.put(key, err));
            finishRun();
        });
    }

    private void finishRun() {
        main.post(() -> {
            busy = false;
            confirm.disarm();
            statusView.setText("");
        });
        CleanupInventory.Snapshot s = CleanupInventory.load(this, rights);
        main.post(() -> {
            if (isDestroyed()) return;
            snap = s;
            rebuild();
        });
    }

    private void pressOk() {
        if (busy) return;
        switch (zone) {
            case AUTO:
                runAuto();
                return;
            case LIST: {
                CleanupList.Row row = focus == null ? null : focus.row();
                CleanupList.Col col = focus == null ? null : focus.col();
                if (row == null || col == null) return;
                String key = focus.confirmKey();
                if (!focus.press(confirm)) {
                    refreshNotes();
                    return;
                }
                runOp(key, row.op(col), row.pkg);
                return;
            }
            default: {
                if (removedIndex >= shownRemoved.size()) return;
                String pkg = shownRemoved.get(removedIndex).pkg;
                String key = KEY_RESTORE + pkg;
                if (!confirm.press(key)) {
                    refreshNotes();
                    return;
                }
                runOp(key, CleanupSections.Op.RESTORE, pkg);
            }
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Builds the model from the inventory snapshot and cached stats, then rebinds the views; keeps focus on the same package. */
    private void rebuild() {
        if (snap == null) return;
        long t0 = SystemClock.elapsedRealtime();
        result = CleanupList.build(snap.apps, snap.protectedSet, profile, currentLog(), snap.holdsHome, statsByPackage(),
                descriptions == null ? null : new CleanupList.Descriptions() {
                    @Override public String describe(String pkg, boolean system) { return descriptions.describe(pkg, system); }
                });
        long t1 = SystemClock.elapsedRealtime();
        if (focus == null) focus = new CleanupRowFocus(result.rows);
        else focus.rebind(result.rows);
        if (zone == Zone.LIST && result.rows.isEmpty()) zone = Zone.AUTO;
        if (zone == Zone.REMOVED && result.removed.isEmpty()) zone = result.rows.isEmpty() ? Zone.AUTO : Zone.LIST;
        removedIndex = Math.min(removedIndex, Math.max(0, result.removed.size() - 1));
        int before = appRows.size();
        render();
        Log.i(TAG, "render rows=" + result.rows.size() + " created=" + (appRows.size() - before) + " model=" + (t1 - t0)
                + "ms views=" + (SystemClock.elapsedRealtime() - t1) + "ms");
    }

    private void render() {
        list.removeAllViews();
        shownApps.clear();
        shownRemoved.clear();
        dimmed = null;
        statusView.setText(busy ? statusView.getText() : "");

        addHeader(R.string.cleanup_section_auto, false);
        if (autoView == null) autoView = buildAutoRow();
        autoView.setAlpha(FocusDim.Role.TEXT.unfocused);
        list.addView(autoView, rowParams(dp(44)));
        if (usageDenied) {
            TextView hint = text(getString(R.string.cleanup_no_usage_rights), R.color.warning_yellow, R.font.outfit_light, 14);
            hint.setPadding(dp(16), dp(6), dp(16), 0);
            list.addView(hint);
        }
        appsHeader = addHeader(R.string.cleanup_section_apps, true);
        List<String> toLoad = new ArrayList<>();
        for (CleanupList.Row r : result.rows) {
            AppRow ar = appRows.get(r.pkg);
            if (ar == null) {
                ar = new AppRow(r.pkg);
                appRows.put(r.pkg, ar);
                toLoad.add(r.pkg);
            }
            ar.bind(r);
            ar.view.setAlpha(FocusDim.Role.TEXT.unfocused);
            list.addView(ar.view, rowParams(LinearLayout.LayoutParams.WRAP_CONTENT));
            shownApps.add(ar);
        }
        removedHeader = null;
        if (!result.removed.isEmpty()) {
            removedHeader = addHeader(R.string.cleanup_section_removed, true);
            for (CleanupList.Removed r : result.removed) {
                RemovedRow rr = new RemovedRow(r);
                list.addView(rr.view, rowParams(LinearLayout.LayoutParams.WRAP_CONTENT));
                shownRemoved.add(rr);
                toLoad.add(r.pkg);
            }
        }
        refreshNotes();
        applyFocusAfterLayout(false);
        loadIcons(toLoad);
    }

    private LinearLayout buildAutoRow() {
        LinearLayout v = rowContainer();
        v.addView(text(getString(R.string.cleanup_auto), R.color.text_primary, R.font.outfit_regular, 20),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        autoValue = text("", R.color.text_secondary, R.font.outfit_light, 18);
        v.addView(autoValue);
        return v;
    }

    private String autoText() {
        String err = errors.get(KEY_AUTO);
        if (err != null) return err;
        if (busy) return progress;
        if (confirm.isArmed(KEY_AUTO)) return getString(R.string.cleanup_confirm_again);
        if (result == null || result.profileName == null) return getString(R.string.cleanup_no_profile);
        if (result.autoSteps.isEmpty()) return getString(R.string.cleanup_auto_none_left, result.profileName);
        return getString(R.string.cleanup_auto_actions, result.autoSteps.size(), result.profileName);
    }

    /** The text beside a row's controls: the confirmation prompt of an armed control, else its last error. */
    private String noteFor(String pkg) {
        for (CleanupList.Col c : CleanupList.Col.values()) {
            if (confirm.isArmed(c.name() + ":" + pkg)) return getString(R.string.cleanup_confirm_again);
        }
        for (CleanupList.Col c : CleanupList.Col.values()) {
            String err = errors.get(c.name() + ":" + pkg);
            if (err != null) return err;
        }
        return "";
    }

    private String removedNote(String pkg) {
        String key = KEY_RESTORE + pkg;
        if (confirm.isArmed(key)) return getString(R.string.cleanup_confirm_again);
        String err = errors.get(key);
        return err != null ? err : getString(R.string.cleanup_restore);
    }

    private void refreshNotes() {
        if (autoValue != null) autoValue.setText(autoText());
        for (AppRow r : shownApps) r.note.setText(noteFor(r.pkg));
        for (RemovedRow r : shownRemoved) r.note.setText(removedNote(r.pkg));
    }

    private void loadIcons(List<String> pkgs) {
        List<ImageView> targets = new ArrayList<>();
        for (String pkg : pkgs) {
            if (!iconRequested.add(pkg)) continue;
            ImageView iv = findIcon(pkg);
            if (iv != null) targets.add(iv);
        }
        if (targets.isEmpty()) return;
        icons.execute(() -> {
            for (ImageView iv : targets) {
                String pkg = (String) iv.getTag();
                try {
                    Drawable d = getPackageManager().getApplicationIcon(pkg);
                    main.post(() -> iv.setImageDrawable(d));
                } catch (Exception e) { // icon is cosmetic: a missing or broken one stays blank
                    Log.w(TAG, "icon " + pkg + ": " + e.getMessage());
                }
            }
        });
    }

    private ImageView findIcon(String pkg) {
        AppRow ar = appRows.get(pkg);
        if (ar != null) return ar.icon;
        for (RemovedRow rr : shownRemoved) if (rr.pkg.equals(pkg)) return (ImageView) rr.view.getChildAt(0);
        return null;
    }

    // ---------------------------------------------------------------- view helpers

    private LinearLayout rowContainer() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(16), dp(8), dp(16), dp(8));
        v.setMinimumHeight(dp(56));
        return v;
    }

    private LinearLayout.LayoutParams rowParams(int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
        lp.bottomMargin = dp(4);
        return lp;
    }

    /** A 24dp round-glass control (checkbox, trash, rollback). */
    private ImageView newControl() {
        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
        lp.setMarginStart(dp(24));
        iv.setLayoutParams(lp);
        return iv;
    }

    private ImageView appIcon(String pkg) {
        ImageView iv = new ImageView(this);
        iv.setTag(pkg);
        return iv;
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.setMarginEnd(dp(14));
        return lp;
    }

    private TextView twoLines(String s) {
        TextView t = text(s, R.color.text_secondary, R.font.outfit_light, 13);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View addHeader(int title, boolean gap) {
        TextView header = text(getString(title), R.color.text_secondary, R.font.outfit_regular, 15);
        header.setPadding(dp(16), dp(gap ? 14 : 0), dp(16), dp(6));
        list.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private TextView text(String s, int colorRes, int fontRes, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(colorRes));
        t.setTypeface(getResources().getFont(fontRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    private int dp(int v) { return Math.round(v * density); }

    // ---------------------------------------------------------------- focus and keys

    private void applyFocusAfterLayout(boolean animate) {
        if (layoutPending) return;
        layoutPending = true;
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                layoutPending = false;
                applyFocus(animate);
                return true;
            }
        });
    }

    private void applyFocus(boolean animate) {
        View row;
        View glassTarget;
        View header = null;
        boolean control = false;
        switch (zone) {
            case LIST: {
                if (focus == null || focus.row() == null) return;
                AppRow ar = appRows.get(focus.row().pkg);
                if (ar == null) return;
                row = ar.view;
                CleanupList.Col col = focus.col();
                control = col != null;
                glassTarget = control ? ar.control(col) : row;
                if (focus.rowIndex() == 0) header = appsHeader;
                break;
            }
            case REMOVED:
                if (removedIndex >= shownRemoved.size()) return;
                row = shownRemoved.get(removedIndex).view;
                glassTarget = row;
                if (removedIndex == 0) header = removedHeader;
                break;
            default:
                row = autoView;
                glassTarget = row;
                header = list.getChildAt(0);
        }
        if (row == null) return;
        if (dimmed != row) {
            if (dimmed != null) FocusDim.applyAlpha(dimmed, FocusDim.Role.TEXT, false, animate);
            FocusDim.applyAlpha(row, FocusDim.Role.TEXT, true, animate);
            dimmed = row;
        }
        int margin = dp(8);
        int top = header != null ? header.getTop() : row.getTop();
        if (top - margin < scroll.getScrollY()) {
            scroll.scrollTo(0, Math.max(0, top - margin));
        } else if (row.getBottom() + margin > scroll.getScrollY() + scroll.getHeight()) {
            scroll.scrollTo(0, row.getBottom() + margin - scroll.getHeight());
        }
        RectF target = new RectF();
        if (!Tiles.locate(glassTarget, root, target)) return;
        target.offset(0, -scroll.getScrollY());
        float radius = control ? Tiles.topGlass(target, density) : 12 * density;
        glass.moveTo(target, radius, animate);
    }

    private void move(FocusKey key) {
        boolean changed = false;
        switch (key) {
            case LEFT:
            case RIGHT:
                if (zone == Zone.LIST && focus != null) {
                    changed = focus.move(key == FocusKey.LEFT ? CleanupRowFocus.Dir.LEFT : CleanupRowFocus.Dir.RIGHT);
                }
                break;
            case UP:
                changed = up();
                break;
            default:
                changed = down();
        }
        if (!changed) return;
        confirm.disarm();
        refreshNotes();
        applyFocus(true);
    }

    private enum FocusKey { LEFT, RIGHT, UP, DOWN }

    private boolean up() {
        switch (zone) {
            case LIST:
                if (focus.rowIndex() > 0) return focus.move(CleanupRowFocus.Dir.UP);
                zone = Zone.AUTO;
                return true;
            case REMOVED:
                if (removedIndex > 0) {
                    removedIndex--;
                } else {
                    zone = result.rows.isEmpty() ? Zone.AUTO : Zone.LIST;
                }
                return true;
            default:
                return false;
        }
    }

    private boolean down() {
        switch (zone) {
            case AUTO:
                if (!result.rows.isEmpty()) {
                    zone = Zone.LIST;
                    return true;
                }
                return enterRemoved();
            case LIST:
                if (focus.move(CleanupRowFocus.Dir.DOWN)) return true;
                return enterRemoved();
            default:
                if (removedIndex < shownRemoved.size() - 1) {
                    removedIndex++;
                    return true;
                }
                return false;
        }
    }

    private boolean enterRemoved() {
        if (shownRemoved.isEmpty()) return false;
        zone = Zone.REMOVED;
        removedIndex = 0;
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) finish();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (centerKey.onKey(down, e.getRepeatCount()) == CenterKey.Press.SHORT && result != null) pressOk();
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        if (result == null) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(FocusKey.UP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(FocusKey.DOWN);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(FocusKey.LEFT);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(FocusKey.RIGHT);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }
}
