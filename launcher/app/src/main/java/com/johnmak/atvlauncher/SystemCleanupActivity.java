package com.johnmak.atvlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * System cleanup: an automatic run of the device profile plus per-app lists (remove, disable, drop updates, restore).
 * D-pad list in the settings look; every action needs OK twice. Commands run on a worker through the loopback adb.
 */
public final class SystemCleanupActivity extends Activity {
    private static final String TAG = "AtvCleanup";
    private static final String KEY_AUTO = "auto";

    private final class Row {
        final LinearLayout view;
        final String key;
        View header;
        final TextView value;
        final Runnable ok;

        Row(String key, LinearLayout view, TextView value, Runnable ok) {
            this.key = key;
            this.view = view;
            this.value = value;
            this.ok = ok;
        }
    }

    private View root;
    private ScrollView scroll;
    private LinearLayout list;
    private GlassView glass;
    private TextView statusView;
    private float density;

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, String> errors = new HashMap<>();
    private final CleanupConfirm confirm = new CleanupConfirm();
    private final CenterKey centerKey = new CenterKey();
    private View pendingHeader;
    private boolean layoutPending;
    private int focus;
    private boolean busy;
    private String progress = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService icons = Executors.newSingleThreadExecutor();

    private RequiredRights.Probe rights;
    private LoopbackAdbSession session;
    private CleanupProfile profile;
    private CleanupLog log = CleanupLog.empty();
    private CleanupLog.Storage logStorage;
    private CleanupSections.Result result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher_settings);
        density = getResources().getDisplayMetrics().density;
        root = findViewById(R.id.root);
        scroll = findViewById(R.id.scroll);
        list = findViewById(R.id.list);
        glass = findViewById(R.id.glass);
        statusView = findViewById(R.id.status);
        ((TextView) findViewById(R.id.heading)).setText(R.string.cleanup_title);
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
            main.post(() -> profile = p);
            reload();
        });
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        icons.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- data

    /** Reads the inventory on the worker (the caller is already on it or schedules it) and rebuilds the list. */
    private void reload() {
        CleanupInventory.Snapshot snap = CleanupInventory.load(this, rights);
        main.post(() -> {
            if (isDestroyed()) return;
            result = CleanupSections.build(snap.apps, snap.protectedSet, profile, currentLog(), snap.holdsHome);
            render();
        });
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

    private static boolean connectionLevel(String message, ShellError kind) {
        return kind == ShellError.UNAUTHORIZED || kind == ShellError.UNREACHABLE;
    }

    // ---------------------------------------------------------------- actions

    private void runAuto() {
        if (busy || result == null || result.autoSteps.isEmpty()) return;
        if (!confirm.press(KEY_AUTO)) {
            refreshValues();
            return;
        }
        List<CleanupPlanner.Step> steps = result.autoSteps;
        busy = true;
        errors.remove(KEY_AUTO);
        progress = getString(R.string.cleanup_progress, 0, steps.size());
        refreshValues();
        worker.execute(() -> {
            int done = 0;
            int failed = 0;
            String stop = null;
            for (CleanupPlanner.Step s : steps) {
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
                        if (connectionLevel(r.error(), kind)) {
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
                    refreshValues();
                });
            }
            String summary = stop != null ? stop : getString(R.string.cleanup_done, done - failed, failed);
            main.post(() -> errors.put(KEY_AUTO, summary));
            finishRun();
        });
    }

    private void runItem(String key, CleanupSections.Item item) {
        if (busy) return;
        if (!confirm.press(key)) {
            refreshValues();
            return;
        }
        busy = true;
        errors.remove(key);
        progress = getString(R.string.cleanup_working);
        refreshValues();
        worker.execute(() -> {
            String err = execute(item.op, item.pkg);
            if (err != null) main.post(() -> errors.put(key, err));
            finishRun();
        });
    }

    private void finishRun() {
        main.post(() -> {
            busy = false;
            confirm.disarm();
        });
        reload();
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        rows.clear();
        list.removeAllViews();
        pendingHeader = null;
        statusView.setText("");

        addSection(R.string.cleanup_section_auto);
        addAutoRow();
        addItems(R.string.cleanup_section_remove, "rm", result.removable);
        addItems(R.string.cleanup_section_disable, "off", result.disable);
        addItems(R.string.cleanup_section_updates, "upd", result.updates);
        addItems(R.string.cleanup_section_restore, "back", result.restore);

        focus = Math.max(0, Math.min(focus, rows.size() - 1));
        refreshValues();
        applyFocusAfterLayout(false);
        loadIcons();
    }

    private void addAutoRow() {
        LinearLayout v = rowContainer();
        TextView label = text(getString(R.string.cleanup_auto), R.color.text_primary, R.font.outfit_regular, 20);
        v.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("", R.color.text_secondary, R.font.outfit_light, 18);
        v.addView(value, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addRowView(new Row(KEY_AUTO, v, value, this::runAuto), dp(44));
    }

    private void addItems(int title, String tag, List<CleanupSections.Item> items) {
        if (items.isEmpty()) return;
        addSection(title);
        for (CleanupSections.Item item : items) {
            String key = tag + ":" + item.pkg;
            LinearLayout v = rowContainer();
            ImageView icon = new ImageView(this);
            icon.setTag(item.pkg);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(32), dp(32));
            ilp.setMarginEnd(dp(14));
            v.addView(icon, ilp);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(item.label, R.color.text_primary, R.font.outfit_regular, 20));
            col.addView(text(item.pkg, R.color.text_secondary, R.font.outfit_light, 13));
            v.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView value = text("", R.color.text_secondary, R.font.outfit_light, 16);
            v.addView(value, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            Row row = new Row(key, v, value, () -> runItem(key, item));
            row.value.setTag(item);
            addRowView(row, dp(56));
        }
    }

    private String valueFor(Row r) {
        String err = errors.get(r.key);
        if (err != null) return err;
        if (busy && r.key.equals(KEY_AUTO)) return progress;
        if (confirm.isArmed(r.key)) return getString(R.string.cleanup_confirm);
        if (r.key.equals(KEY_AUTO)) return autoText();
        Object tag = r.value.getTag();
        if (tag instanceof CleanupSections.Item && ((CleanupSections.Item) tag).recommended) {
            return getString(((CleanupSections.Item) tag).op.isUndo() ? R.string.cleanup_ours : R.string.cleanup_recommended);
        }
        return "";
    }

    private String autoText() {
        if (busy) return progress;
        if (result == null || result.profileName == null) return getString(R.string.cleanup_no_profile);
        if (result.autoSteps.isEmpty()) return getString(R.string.cleanup_auto_none_left, result.profileName);
        return getString(R.string.cleanup_auto_actions, result.autoSteps.size(), result.profileName);
    }

    private void refreshValues() {
        for (Row r : rows) {
            r.value.setText(valueFor(r));
        }
    }

    private void loadIcons() {
        List<ImageView> targets = new ArrayList<>();
        for (Row r : rows) {
            View first = r.view.getChildAt(0);
            if (first instanceof ImageView) targets.add((ImageView) first);
        }
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

    private LinearLayout rowContainer() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(16), 0, dp(16), 0);
        return v;
    }

    private void addSection(int title) {
        TextView header = text(getString(title), R.color.text_secondary, R.font.outfit_regular, 15);
        header.setPadding(dp(16), dp(rows.isEmpty() ? 0 : 14), dp(16), dp(6));
        list.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        pendingHeader = header;
    }

    private void addRowView(Row row, int height) {
        row.header = pendingHeader;
        pendingHeader = null;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
        lp.bottomMargin = dp(4);
        list.addView(row.view, lp);
        rows.add(row);
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
        if (rows.isEmpty()) return;
        for (int i = 0; i < rows.size(); i++) {
            FocusDim.applyAlpha(rows.get(i).view, FocusDim.Role.TEXT, i == focus, animate);
        }
        View v = rows.get(focus).view;
        View header = rows.get(focus).header;
        int margin = dp(8);
        int top = header != null ? header.getTop() : v.getTop();
        if (top - margin < scroll.getScrollY()) {
            scroll.scrollTo(0, Math.max(0, top - margin));
        } else if (v.getBottom() + margin > scroll.getScrollY() + scroll.getHeight()) {
            scroll.scrollTo(0, v.getBottom() + margin - scroll.getHeight());
        }
        RectF target = new RectF();
        if (!Tiles.locate(v, root, target)) return;
        target.offset(0, -scroll.getScrollY());
        glass.moveTo(target, 12 * density, animate);
    }

    private void moveFocus(int delta) {
        if (rows.isEmpty()) return;
        int next = Math.max(0, Math.min(rows.size() - 1, focus + delta));
        if (next == focus) return;
        focus = next;
        confirm.disarm();
        refreshValues();
        applyFocus(true);
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
            if (centerKey.onKey(down, e.getRepeatCount()) == CenterKey.Press.SHORT && !rows.isEmpty()) {
                rows.get(focus).ok.run();
            }
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveFocus(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveFocus(1);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }
}
