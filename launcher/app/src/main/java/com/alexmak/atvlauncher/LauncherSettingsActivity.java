package com.alexmak.atvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Launcher settings in titled sections (appearance, content, network, about). A D-pad list with the glass highlight
 * behind the focused row; section headers are not rows, so focus skips them. LEFT/RIGHT cycle a value, OK toggles or
 * activates. Manual city entry and the Kinopoisk token are second pages (text field + results). Changes are saved at
 * once; the home screen reads them in onStart.
 */
public final class LauncherSettingsActivity extends Activity {
    private static final String TAG = "AtvSettings";
    /** Boolean extra: open with the «Город» row focused (weather item without a city). */
    static final String EXTRA_FOCUS_CITY = "com.alexmak.atvlauncher.FOCUS_CITY";

    private static final String STATE_ROW = "focus_row";

    static void open(Context context, boolean focusCity) {
        context.startActivity(new Intent(context, LauncherSettingsActivity.class).putExtra(EXTRA_FOCUS_CITY, focusCity));
    }

    /** One focusable line: label on the left, value (or the search field) on the right. */
    private final class Row {
        final LinearLayout view;
        /** Header of the section this row opens (shown above it); null for the other rows. */
        View header;
        final TextView value;
        final Supplier<String> valueText;
        final Runnable ok;
        final Runnable left;
        final Runnable right;

        Row(String label, TextView value, Supplier<String> valueText, Runnable ok, Runnable left, Runnable right) {
            this.value = value;
            this.valueText = valueText;
            this.ok = ok;
            this.left = left;
            this.right = right;
            view = new LinearLayout(LauncherSettingsActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(16);
            view.setPadding(pad, 0, pad, 0);
            TextView l = text(label, R.color.text_primary, R.font.outfit_regular);
            view.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            view.addView(value, valueText == null
                    ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        /** Cyclic rows show arrows around the value while focused. */
        void refresh(boolean focused) {
            if (valueText == null) return;
            String v = valueText.get();
            value.setText(left != null && focused ? "‹  " + v + "  ›" : v);
        }
    }

    private enum Page { MAIN, SEARCH, TOKEN }

    private View root;
    private ScrollView scroll;
    private LinearLayout list;
    private GlassView glass;
    private TextView statusView;
    private TextView videoNote;
    private float density;

    private LauncherSettingsStore store;
    private WeatherPrefs weatherPrefs;
    private KinopoiskTokenStore tokens;
    private LauncherSettings settings;
    private List<VpnApps.App> vpnApps;

    private final List<Row> rows = new ArrayList<>();
    private final CenterKey centerKey = new CenterKey();
    private Page page = Page.MAIN;
    private int focus;
    private int cityRow;
    private int manualRow;
    private int tokenRow;
    /** Section header added by {@link #addSection}, attached to the next row. */
    private View pendingHeader;
    private boolean layoutPending;

    private EditText input;
    private boolean editing;
    private boolean imeSeen;
    private int resultsStart;

    private boolean busy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

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
        store = new LauncherSettingsStore(this);
        weatherPrefs = new WeatherPrefs(this);
        tokens = new KinopoiskTokenStore(this);
        settings = store.load();
        vpnApps = VpnAppsLoader.installed(getPackageManager());
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            onImeVisible(insets.isVisible(WindowInsets.Type.ime()));
            return insets;
        });
        if (savedInstanceState != null) showMain(savedInstanceState.getInt(STATE_ROW));
        else showMain(getIntent().getBooleanExtra(EXTRA_FOCUS_CITY, false) ? -1 : 0);
    }

    /** Keeps the focused row over the recreate() that applies a new UI scale. */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ROW, focus);
    }

    /** Back from the app picker: the «N из M» values changed. */
    @Override
    protected void onStart() {
        super.onStart();
        if (page == Page.MAIN && !rows.isEmpty()) refreshValues();
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

    // ---------------------------------------------------------------- pages

    /** {@code focusRow} -1 = the «Город» row. */
    private void showMain(int focusRow) {
        page = Page.MAIN;
        clearRows();

        addSection(R.string.settings_section_appearance);
        addRow(R.string.settings_ui_scale, () -> getString(R.string.settings_percent, settings.uiScalePercent), null,
                () -> changeUiScale(settings.prevUiScale()), () -> changeUiScale(settings.nextUiScale()));
        addRow(R.string.settings_preview_scale, () -> getString(R.string.settings_percent, settings.previewScalePercent), null,
                () -> change(settings.prevPreviewScale()), () -> change(settings.nextPreviewScale()));
        addRow(R.string.settings_dim, () -> onOff(settings.dimEnabled),
                () -> change(settings.withDimEnabled(!settings.dimEnabled)),
                () -> change(settings.withDimEnabled(!settings.dimEnabled)),
                () -> change(settings.withDimEnabled(!settings.dimEnabled)));
        addRow(R.string.settings_dim_delay, () -> getString(R.string.settings_seconds, settings.dimDelaySec), null,
                () -> change(settings.prevDimDelay()), () -> change(settings.nextDimDelay()));
        addRow(R.string.settings_screensaver, () -> onOff(settings.screensaverEnabled),
                this::toggleScreensaver, this::toggleScreensaver, this::toggleScreensaver);
        addRow(R.string.settings_screensaver_delay,
                () -> getString(R.string.settings_minutes, settings.screensaverDelayMin), null,
                () -> change(settings.prevScreensaverDelay()), () -> change(settings.nextScreensaverDelay()));
        addRow(R.string.settings_screensaver_type, this::typeText, null,
                () -> changeType(settings.prevScreensaverType()), () -> changeType(settings.nextScreensaverType()));
        videoNote = addNote(getString(R.string.settings_screensaver_hint));
        updateVideoNote();

        addSection(R.string.settings_section_content);
        addRow(R.string.settings_sources_recommendations, () -> sourcesText(FeedSource.RECOMMENDATIONS),
                () -> AppPickerActivity.open(this, FeedSource.RECOMMENDATIONS), null, null);
        addRow(R.string.settings_sources_continue, () -> sourcesText(FeedSource.CONTINUE),
                () -> AppPickerActivity.open(this, FeedSource.CONTINUE), null, null);
        tokenRow = rows.size();
        addRow(R.string.settings_kinopoisk_token, this::tokenText, this::showToken, null, null);
        cityRow = rows.size();
        addRow(R.string.settings_city, this::cityText, null, null, null);
        addRow(R.string.settings_detect, null, this::detectCity, null, null);
        manualRow = rows.size();
        addRow(R.string.settings_manual, null, this::showSearch, null, null);

        addSection(R.string.settings_section_network);
        addRow(R.string.settings_vpn_button, this::vpnButtonText, this::toggleVpnButton,
                this::toggleVpnButton, this::toggleVpnButton);
        addRow(R.string.settings_vpn_app, this::vpnAppText, null,
                () -> changeVpnApp(-1), () -> changeVpnApp(1));

        // «Проверить обновления» joins this section later: one more addRow here
        addSection(R.string.settings_section_about);
        addRow(R.string.settings_about_name, () -> BuildConfig.VERSION_NAME, null, null, null);

        focus = focusRow < 0 ? cityRow : focusRow;
        status("");
        applyFocusAfterLayout(false);
    }

    private void showSearch() {
        page = Page.SEARCH;
        clearRows();
        input = createInput(R.string.settings_search_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorInfo.IME_ACTION_SEARCH, this::search);
        addRow(R.string.settings_city, input, null, this::startEditing, null, null);
        addRow(R.string.settings_search_go, null, () -> search(), null, null);
        resultsStart = rows.size();
        focus = 0;
        status("");
        applyFocusAfterLayout(false);
    }

    /** Second page for the Kinopoisk token: a text field and «Сохранить»; empty input removes the token. */
    private void showToken() {
        page = Page.TOKEN;
        clearRows();
        input = createInput(R.string.settings_token_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_DONE,
                this::saveToken);
        addRow(R.string.settings_kinopoisk_token, input, null, this::startEditing, null, null);
        addRow(R.string.settings_token_save, null, this::saveToken, null, null);
        focus = 0;
        status("");
        applyFocusAfterLayout(false);
    }

    private void saveToken() {
        stopEditing();
        String raw = input.getText().toString();
        if (raw.trim().isEmpty()) {
            tokens.clear();
            showMain(tokenRow);
            return;
        }
        FetchResult<String> saved = tokens.save(raw);
        if (saved.isOk()) {
            showMain(tokenRow);
        } else {
            Log.w(TAG, "token rejected: " + saved.error());
            status(getString(R.string.settings_token_invalid));
        }
    }

    /** A borderless single-line field in the value cell; {@code onAction} runs when the keyboard's action key is pressed. */
    private EditText createInput(int hint, int inputType, int imeAction, Runnable onAction) {
        EditText field = new EditText(this);
        styleText(field, R.color.text_primary, R.font.outfit_regular);
        field.setHint(hint);
        field.setHintTextColor(getColor(R.color.text_secondary));
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setImeOptions(imeAction);
        field.setFocusable(false);
        field.setOnEditorActionListener((v, actionId, event) -> {
            stopEditing();
            onAction.run();
            return true;
        });
        return field;
    }

    private void clearRows() {
        pendingHeader = null;
        rows.clear();
        list.removeAllViews();
    }

    private void addRow(int label, Supplier<String> valueText, Runnable ok, Runnable left, Runnable right) {
        addRow(label, text("", R.color.text_secondary, R.font.outfit_light), valueText, ok, left, right);
    }

    private void addRow(int label, TextView value, Supplier<String> valueText, Runnable ok, Runnable left, Runnable right) {
        addRowView(new Row(getString(label), value, valueText, ok, left, right));
    }

    /** A non-focusable title above the next row; focus never lands on it. */
    private void addSection(int title) {
        TextView header = text(getString(title), R.color.text_secondary, R.font.outfit_regular);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setPadding(dp(16), dp(rows.isEmpty() ? 0 : 14), dp(16), dp(6));
        list.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        pendingHeader = header;
    }

    private void addRowView(Row row) {
        row.header = pendingHeader;
        pendingHeader = null;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.bottomMargin = dp(4);
        list.addView(row.view, lp);
        rows.add(row);
        row.refresh(false);
    }

    private TextView addNote(String s) {
        TextView note = text(s, R.color.text_secondary, R.font.outfit_light);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        note.setPadding(dp(16), 0, dp(16), dp(10));
        list.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return note;
    }

    private TextView text(String s, int colorRes, int fontRes) {
        TextView t = new TextView(this);
        t.setText(s);
        styleText(t, colorRes, fontRes);
        return t;
    }

    private void styleText(TextView t, int colorRes, int fontRes) {
        t.setTextColor(getColor(colorRes));
        t.setTypeface(getResources().getFont(fontRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- values

    private String onOff(boolean on) {
        return getString(on ? R.string.settings_on : R.string.settings_off);
    }

    /** «все» or «N из M» for the apps that publish into a feed row. */
    private String sourcesText(FeedSource source) {
        Set<String> excluded = store.excluded(source);
        if (excluded.isEmpty()) return getString(R.string.settings_sources_all);
        AppLoader.Result apps = AppCache.apps();
        if (apps == null) return getString(R.string.settings_sources_unknown);
        int total = apps.byPackage.size();
        int included = ExcludedApps.includedCount(apps.byPackage.keySet(), excluded);
        return included == total ? getString(R.string.settings_sources_all)
                : getString(R.string.settings_sources_some, included, total);
    }

    private String tokenText() {
        String token = tokens.token();
        return token.isEmpty() ? getString(R.string.settings_token_none) : KinopoiskTokenStore.mask(token);
    }

    private String cityText() {
        GeoLocation g = weatherPrefs.location();
        if (g == null) return getString(R.string.settings_city_none);
        String city = g.cityRu.isEmpty() ? g.timezoneId : g.cityRu;
        return city + " · " + TimezoneLabel.of(g.timezoneId, System.currentTimeMillis());
    }

    private void change(LauncherSettings next) {
        settings = next;
        store.save(settings);
        refreshValues();
    }

    /** The scale applies to the whole activity: save, then recreate it (the home screen re-reads it in onStart). */
    private void changeUiScale(LauncherSettings next) {
        if (next.uiScalePercent == settings.uiScalePercent) return;
        settings = next;
        store.save(settings);
        recreate();
    }

    private String typeText() {
        return getString(settings.screensaverType == LauncherSettings.ScreensaverType.NASA_VIDEO
                ? R.string.settings_type_video : R.string.settings_type_starfield);
    }

    private void changeType(LauncherSettings next) {
        change(next);
        updateVideoNote();
    }

    /** The note under the type row: the screensaver hint, or the NASA credit when that type is selected. */
    private void updateVideoNote() {
        if (videoNote == null || page != Page.MAIN) return;
        if (settings.screensaverType == LauncherSettings.ScreensaverType.NASA_VIDEO) {
            videoNote.setText(getString(R.string.settings_video_credit, getString(R.string.nasa_credit)));
        } else {
            videoNote.setText(R.string.settings_screensaver_hint);
        }
    }

    private String vpnButtonText() {
        return getString(settings.vpnButtonVisible ? R.string.settings_vpn_show : R.string.settings_vpn_hide);
    }

    private String vpnAppText() {
        String pkg = currentVpnPackage();
        return pkg.isEmpty() ? getString(R.string.settings_vpn_none) : VpnApps.labelOf(pkg, vpnApps);
    }

    private String currentVpnPackage() {
        return VpnApps.choose(settings.vpnPackage, vpnApps, getString(R.string.vpn_default_package));
    }

    private void toggleVpnButton() {
        change(settings.withVpnButtonVisible(!settings.vpnButtonVisible));
    }

    private void changeVpnApp(int step) {
        String next = VpnApps.cycle(currentVpnPackage(), vpnApps, step);
        if (!next.isEmpty()) change(settings.withVpnPackage(next));
    }

    private void toggleScreensaver() {
        change(settings.withScreensaverEnabled(!settings.screensaverEnabled));
        status(SystemScreensaver.apply(this, settings.screensaverEnabled)
                ? "" : getString(R.string.settings_screensaver_denied));
    }

    // ---------------------------------------------------------------- city

    private void detectCity() {
        if (busy) return;
        if (isVpnActive()) {
            status(getString(R.string.weather_vpn_off));
            return;
        }
        busy = true;
        status(getString(R.string.settings_detecting));
        worker.execute(() -> {
            FetchResult<GeoLocation> r = GeoIpClient.fetch();
            main.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (r.isOk()) {
                    weatherPrefs.saveLocation(r.value());
                    status("");
                    refreshValues();
                } else {
                    Log.w(TAG, "geo ip failed: " + r.error());
                    status(getString(R.string.settings_detect_failed));
                }
            });
        });
    }

    private boolean isVpnActive() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private void search() {
        if (busy) return;
        String query = input.getText().toString().trim();
        if (query.isEmpty()) return;
        busy = true;
        status(getString(R.string.settings_searching));
        worker.execute(() -> {
            FetchResult<List<GeocodingModel.Place>> r = GeocodingClient.search(query);
            main.post(() -> {
                busy = false;
                if (isDestroyed() || page != Page.SEARCH) return;
                if (r.isOk()) {
                    showResults(r.value());
                } else {
                    Log.w(TAG, "geocoding failed: " + r.error());
                    status(getString(R.string.settings_search_failed));
                }
            });
        });
    }

    private void showResults(List<GeocodingModel.Place> places) {
        while (rows.size() > resultsStart) {
            list.removeView(rows.remove(rows.size() - 1).view);
        }
        for (GeocodingModel.Place p : places) {
            TextView name = text(p.label(), R.color.text_primary, R.font.outfit_regular);
            Row row = new Row("", name, null, () -> pick(p), null, null);
            // the result line replaces the empty label cell: the place name takes the whole row
            row.view.removeAllViews();
            row.view.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView tz = text(TimezoneLabel.of(p.timezoneId, System.currentTimeMillis()),
                    R.color.text_secondary, R.font.outfit_light);
            row.view.addView(tz);
            addRowView(row);
        }
        status(places.isEmpty() ? getString(R.string.settings_nothing_found) : "");
        if (!places.isEmpty()) focus = resultsStart;
        applyFocusAfterLayout(true);
    }

    private void pick(GeocodingModel.Place place) {
        weatherPrefs.saveLocation(place.toLocation());
        showMain(-1);
    }

    // ---------------------------------------------------------------- text entry

    private void startEditing() {
        editing = true;
        imeSeen = false;
        input.setFocusableInTouchMode(true);
        input.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(input, 0);
    }

    private void stopEditing() {
        if (!editing) return;
        editing = false;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        input.setFocusable(false);
    }

    /** The keyboard was dismissed by the system (Back goes to the IME first): leave text entry. */
    private void onImeVisible(boolean visible) {
        if (visible) imeSeen = true;
        else if (editing && imeSeen) stopEditing();
    }

    // ---------------------------------------------------------------- focus

    private void status(String s) {
        statusView.setText(s);
    }

    private void refreshValues() {
        for (int i = 0; i < rows.size(); i++) rows.get(i).refresh(i == focus);
    }

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
            Row r = rows.get(i);
            r.refresh(i == focus);
            FocusDim.applyAlpha(r.view, FocusDim.Role.TEXT, i == focus, animate);
        }
        View v = rows.get(focus).view;
        View header = rows.get(focus).header;
        int margin = dp(8);
        // a section's first row scrolls its header into view too
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) onBack();
            return true;
        }
        if (editing) return super.dispatchKeyEvent(e);
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (centerKey.onKey(down, e.getRepeatCount()) == CenterKey.Press.SHORT) {
                Runnable ok = rows.get(focus).ok;
                if (ok != null) ok.run();
            }
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (e.getRepeatCount() == 0) moveFocus(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (e.getRepeatCount() == 0) moveFocus(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                runIfSet(rows.get(focus).left);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                runIfSet(rows.get(focus).right);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static void runIfSet(Runnable r) {
        if (r != null) r.run();
    }

    private void moveFocus(int delta) {
        int next = Math.max(0, Math.min(rows.size() - 1, focus + delta));
        if (next == focus) return;
        focus = next;
        applyFocus(true);
    }

    private void onBack() {
        if (editing) {
            stopEditing();
        } else if (page == Page.SEARCH) {
            showMain(manualRow);
        } else if (page == Page.TOKEN) {
            showMain(tokenRow);
        } else {
            finish();
        }
    }
}
