package com.johnmak.atvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picks the apps whose programs may appear in one feed row ({@link FeedSource}): every launchable app with its icon,
 * name and a checkbox. UP/DOWN move, OK flips the app, BACK closes. The excluded set is saved on every flip, so
 * closing by any route keeps the choice; apps installed later are included by default.
 */
public final class AppPickerActivity extends Activity {
    private static final String EXTRA_SOURCE = "com.johnmak.atvlauncher.FEED_SOURCE";
    private static final String STATE_ROW = "focus_row";

    static void open(Context context, FeedSource source) {
        context.startActivity(new Intent(context, AppPickerActivity.class).putExtra(EXTRA_SOURCE, source.name()));
    }

    /** One focusable line: icon, app name and its checkbox. */
    private static final class Row {
        final String packageName;
        final LinearLayout view;
        final TextView box;

        Row(String packageName, LinearLayout view, TextView box) {
            this.packageName = packageName;
            this.view = view;
            this.box = box;
        }
    }

    private View root;
    private ScrollView scroll;
    private LinearLayout list;
    private GlassView glass;
    private TextView statusView;
    private float density;

    private FeedSource source;
    private LauncherSettingsStore store;
    private Set<String> excluded;
    private final List<Row> rows = new ArrayList<>();
    private final CenterKey centerKey = new CenterKey();
    private int focus;
    private boolean layoutPending;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);
        density = getResources().getDisplayMetrics().density;
        root = findViewById(R.id.root);
        scroll = findViewById(R.id.scroll);
        list = findViewById(R.id.list);
        glass = findViewById(R.id.glass);
        statusView = findViewById(R.id.status);
        String name = getIntent().getStringExtra(EXTRA_SOURCE);
        if (name == null) throw new IllegalStateException("feed source extra is required");
        source = FeedSource.valueOf(name);
        ((TextView) findViewById(R.id.heading)).setText(source.titleRes);
        store = new LauncherSettingsStore(this);
        excluded = store.excluded(source);
        focus = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_ROW);

        AppLoader.Result cached = AppCache.apps();
        if (cached != null) {
            show(cached);
        } else {
            statusView.setText(R.string.picker_loading);
            worker.execute(() -> {
                AppLoader.Result loaded = AppCache.loader(this).load();
                AppCache.setApps(loaded);
                main.post(() -> {
                    if (isDestroyed()) return;
                    statusView.setText(R.string.picker_hint);
                    show(loaded);
                });
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ROW, focus);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void show(AppLoader.Result apps) {
        list.removeAllViews();
        rows.clear();
        for (AppEntry app : apps.apps) addRow(app);
        focus = Math.max(0, Math.min(rows.size() - 1, focus));
        if (!rows.isEmpty()) applyFocusAfterLayout(false);
    }

    private void addRow(AppEntry app) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(16), 0, dp(16), 0);

        ImageView icon = new ImageView(this);
        icon.setImageBitmap(app.icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconLp.setMarginEnd(dp(16));
        view.addView(icon, iconLp);

        TextView name = new TextView(this);
        name.setText(app.label);
        name.setTextColor(getColor(R.color.text_primary));
        name.setTypeface(getResources().getFont(R.font.outfit_regular));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        view.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView box = new TextView(this);
        box.setGravity(Gravity.CENTER);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.setIncludeFontPadding(false);
        view.addView(box, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.bottomMargin = dp(4);
        list.addView(view, lp);
        Row row = new Row(app.packageName, view, box);
        rows.add(row);
        renderBox(row);
    }

    /** Filled square with a tick when the app is included, an empty outline when it is excluded. */
    private void renderBox(Row row) {
        boolean on = ExcludedApps.isIncluded(excluded, row.packageName);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        if (on) {
            bg.setColor(getColor(R.color.text_primary));
        } else {
            bg.setStroke(dp(2), getColor(R.color.text_secondary));
        }
        row.box.setBackground(bg);
        row.box.setText(on ? "✓" : "");
        row.box.setTextColor(0xFF000000);
    }

    private void toggleFocused() {
        Row row = rows.get(focus);
        excluded = ExcludedApps.toggled(excluded, row.packageName);
        store.saveExcluded(source, excluded);
        renderBox(row);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- focus

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
        for (int i = 0; i < rows.size(); i++) {
            FocusDim.applyAlpha(rows.get(i).view, FocusDim.Role.TEXT, i == focus, animate);
        }
        View v = rows.get(focus).view;
        int margin = dp(8);
        if (v.getTop() - margin < scroll.getScrollY()) {
            scroll.scrollTo(0, Math.max(0, v.getTop() - margin));
        } else if (v.getBottom() + margin > scroll.getScrollY() + scroll.getHeight()) {
            scroll.scrollTo(0, v.getBottom() + margin - scroll.getHeight());
        }
        RectF target = new RectF();
        if (!Tiles.locate(v, root, target)) return;
        target.offset(0, -scroll.getScrollY());
        glass.moveTo(target, 12 * density, animate);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) finish();
            return true;
        }
        if (rows.isEmpty()) return super.dispatchKeyEvent(e);
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (centerKey.onKey(down, e.getRepeatCount()) == CenterKey.Press.SHORT) toggleFocused();
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveFocus(-1, e.getRepeatCount() == 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveFocus(1, e.getRepeatCount() == 0);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void moveFocus(int delta, boolean animate) {
        int next = Math.max(0, Math.min(rows.size() - 1, focus + delta));
        if (next == focus) return;
        focus = next;
        applyFocus(animate);
    }
}
