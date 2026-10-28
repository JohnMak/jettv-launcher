package com.johnmak.atvlauncher;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.johnmak.atvlauncher.SettingsTabsNav.Key;
import com.johnmak.atvlauncher.SettingsTabsNav.State;
import com.johnmak.atvlauncher.SettingsTabsNav.Zone;

/**
 * Launcher settings in tabs (general, interface, screensaver, optimization) under the missing-rights warning rows.
 * A D-pad list with the glass highlight behind the focused row or tab (navigation: {@link SettingsTabsNav}).
 * LEFT/RIGHT cycle a value, OK toggles or activates. Manual city entry is a second page (text field + results). Changes are saved at
 * once; the home screen reads them in onStart.
 */
public final class LauncherSettingsActivity extends Activity {
    private static final String TAG = "AtvSettings";
    /** Boolean extra: open with the «Город» row focused (weather item without a city). */
    static final String EXTRA_FOCUS_CITY = "com.johnmak.atvlauncher.FOCUS_CITY";
    /** Boolean extra: open with the missing-rights warning row focused (first start). */
    static final String EXTRA_FOCUS_RIGHTS = "com.johnmak.atvlauncher.FOCUS_RIGHTS";

    private static final String STATE_ZONE = "focus_zone";
    private static final String STATE_TAB = "focus_tab";
    private static final String STATE_ROW = "focus_row";

    static void open(Context context, boolean focusCity) {
        context.startActivity(new Intent(context, LauncherSettingsActivity.class).putExtra(EXTRA_FOCUS_CITY, focusCity));
    }

    /** One focusable line: label on the left, value (or the search field) on the right. */
    private final class Row {
        final LinearLayout view;
        final TextView label;
        final TextView value;
        final Supplier<String> valueText;
        final Runnable ok;
        final Runnable left;
        final Runnable right;
        /** When set, the cyclic arrows show only while this returns true (a row cyclable only in some states, e.g. the
         * ART category row while its catalog is loaded). Null = a plain cyclic row (arrows whenever {@link #left} is set). */
        private BooleanSupplier cyclable;

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
            this.label = l;
            view.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            view.addView(value, valueText == null
                    ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        /** The attention colour on label and value of this row only. */
        void warn() {
            int c = getColor(R.color.warning_yellow);
            label.setTextColor(c);
            value.setTextColor(c);
        }

        /** Cyclic rows show arrows around the value while focused (and, when gated, only while {@link #cyclable} holds). */
        void refresh(boolean focused) {
            if (valueText == null) return;
            String v = valueText.get();
            boolean arrows = focused && left != null && (cyclable == null || cyclable.getAsBoolean());
            value.setText(arrows ? "‹  " + v + "  ›" : v);
        }

        /** Gates the cyclic arrows on {@code c}; returns this row for call-site chaining. */
        Row cyclable(BooleanSupplier c) {
            this.cyclable = c;
            return this;
        }
    }

    private enum Page { MAIN, SEARCH }

    private View root;
    private ScrollView scroll;
    private LinearLayout list;
    private LinearLayout topList;
    private LinearLayout tabStrip;
    private final List<TextView> tabViews = new ArrayList<>();
    private GlassView glass;
    private TextView statusView;
    private TextView videoNote;
    private float density;

    private LauncherSettingsStore store;
    private Motion motion = Motion.ANIMATED;
    private WeatherPrefs weatherPrefs;
    private LauncherSettings settings;
    private List<VpnApps.App> vpnApps;

    /** Missing-rights warning rows above the tabs. */
    private final List<Row> topRows = new ArrayList<>();
    /** Rows of the selected tab (or of the search page). */
    private final List<Row> rows = new ArrayList<>();
    private final CenterKey centerKey = new CenterKey();
    private Page page = Page.MAIN;
    private State nav = SettingsTabsNav.tabs(0);
    private int cityRow;
    private int manualRow;
    private boolean layoutPending;
    private int updateRow = -1;
    private int rightsRow = -1;
    private int homeRow = -1;
    private boolean rightsRunning;
    private boolean homeRunning;
    private String rightsError = "";
    private String homeError = "";
    private RequiredRights.Probe rightsProbe;
    private RightsFixer fixer;
    private final UpdateController.Listener updateListener = state -> refreshUpdateRow();

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
        topList = findViewById(R.id.top_rows);
        tabStrip = findViewById(R.id.tabs);
        glass = findViewById(R.id.glass);
        statusView = findViewById(R.id.status);
        store = new LauncherSettingsStore(this);
        weatherPrefs = new WeatherPrefs(this);
        settings = store.load();
        vpnApps = VpnAppsLoader.installed(getPackageManager());
        rightsProbe = RequiredRights.android(this);
        fixer = new RightsFixer(LoopbackAdbSession.shared(this), PackageName.parse(getPackageName()).value(),
                rightsProbe, worker, main::post);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            onImeVisible(insets.isVisible(WindowInsets.Type.ime()));
            return insets;
        });
        buildTabs();
        if (savedInstanceState != null) {
            Zone zone = Zone.values()[savedInstanceState.getInt(STATE_ZONE)];
            showMain(new State(zone, savedInstanceState.getInt(STATE_TAB), savedInstanceState.getInt(STATE_ROW)));
        } else if (getIntent().getBooleanExtra(EXTRA_FOCUS_CITY, false)) {
            showMain(new State(Zone.LIST, 0, -1));
        } else if (getIntent().getBooleanExtra(EXTRA_FOCUS_RIGHTS, false)) {
            showMain(new State(Zone.TOP_ROWS, 0, 0));
        } else {
            showMain(SettingsTabsNav.tabs(0));
        }
    }

    /** Rights may have been granted or revoked elsewhere (system settings, adb): recompute the top rows. */
    @Override
    protected void onResume() {
        super.onResume();
        motion = Motion.of(store.load());
        refreshRights();
    }

    /** Keeps the focus (zone, tab, row) over the recreate() that applies a new UI scale. */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_ZONE, nav.zone.ordinal());
        outState.putInt(STATE_TAB, nav.tab);
        outState.putInt(STATE_ROW, nav.row);
    }

    /** Back from the app picker: the «N из M» values changed. */
    @Override
    protected void onStart() {
        super.onStart();
        if (page == Page.MAIN && !rows.isEmpty()) refreshValues();
        UpdateController.instance(this).addListener(updateListener);
    }

    @Override
    protected void onStop() {
        UpdateController.instance(this).removeListener(updateListener);
        super.onStop();
    }

    private String updateText() {
        return UpdateStatusText.of(UpdateController.instance(this).state(), (id, args) -> getString(id, args));
    }

    /** Only the update row's value changes while a check runs; the list is not rebuilt. */
    private void refreshUpdateRow() {
        if (page != Page.MAIN || updateRow < 0 || updateRow >= rows.size()) return;
        rows.get(updateRow).refresh(nav.zone == Zone.LIST && updateRow == nav.row);
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

    private static final int TAB_GENERAL = 0;
    private static final int TAB_INTERFACE = 1;
    private static final int TAB_SCREENSAVER = 2;
    private static final int TAB_OPTIMIZATION = 3;

    /** The tab strip is built once; the selected tab is only recoloured. */
    private void buildTabs() {
        int[] titles = {R.string.settings_tab_general, R.string.settings_tab_interface,
                R.string.settings_tab_screensaver, R.string.settings_tab_optimization};
        for (int title : titles) {
            TextView t = text(getString(title), R.color.text_primary, R.font.outfit_regular);
            t.setPadding(dp(16), dp(8), dp(16), dp(8));
            tabStrip.addView(t, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tabViews.add(t);
        }
    }

    /**
     * Rebuilds the warning rows and the selected tab's list and focuses {@code focusState}; a LIST row of -1 means the
     * «Город» row.
     */
    private void showMain(State focusState) {
        page = Page.MAIN;
        clearRows();
        rightsRow = -1;
        homeRow = -1;
        tabStrip.setVisibility(View.VISIBLE);
        topList.setVisibility(View.VISIBLE);

        List<RequiredRights.Right> missing = RequiredRights.missing(rightsProbe);
        if (!RequiredRights.withoutHome(missing).isEmpty()) {
            rightsRow = topRows.size();
            addTopRow(R.string.settings_rights_warning, () -> rightsText(RequiredRights.missing(rightsProbe)),
                    this::grantRights).warn();
        }
        if (missing.contains(RequiredRights.Right.HOME)) {
            homeRow = topRows.size();
            addTopRow(R.string.settings_set_home, this::homeText, this::setHome);
        }

        int tab = focusState.tab;
        switch (tab) {
            case TAB_GENERAL:
                buildGeneral();
                break;
            case TAB_INTERFACE:
                buildInterface();
                break;
            case TAB_SCREENSAVER:
                buildScreensaver();
                break;
            default:
                buildOptimization();
                break;
        }
        int row = focusState.zone == Zone.LIST && focusState.row < 0 ? cityRow : focusState.row;
        nav = SettingsTabsNav.clamp(new State(focusState.zone, tab, row), topRows.size(), rows.size());
        scroll.scrollTo(0, 0);
        status("");
        applyFocusAfterLayout(false);
    }

    private Row addTopRow(int label, Supplier<String> valueText, Runnable ok) {
        Row row = new Row(getString(label), text("", R.color.text_secondary, R.font.outfit_light), valueText, ok, null, null);
        addRowView(row, topList, topRows);
        return row;
    }

    private void buildGeneral() {
        cityRow = rows.size();
        addRow(R.string.settings_city, this::cityText, null, null, null);
        addRow(R.string.settings_detect, null, this::detectCity, null, null);
        manualRow = rows.size();
        addRow(R.string.settings_manual, null, this::showSearch, null, null);
        addRow(R.string.settings_vpn_button, this::vpnButtonText, this::toggleVpnButton,
                this::toggleVpnButton, this::toggleVpnButton);
        addRow(R.string.settings_vpn_app, this::vpnAppText, null,
                () -> changeVpnApp(-1), () -> changeVpnApp(1));
        addRow(R.string.settings_about_name, () -> BuildConfig.VERSION_NAME, null, null, null);
        updateRow = rows.size();
        addRow(R.string.settings_check_update, this::updateText,
                () -> UpdateController.instance(this).checkNow(), null, null);
    }

    private void buildInterface() {
        addRow(R.string.settings_ui_scale, () -> getString(R.string.settings_percent, settings.uiScalePercent), null,
                () -> changeUiScale(settings.prevUiScale()), () -> changeUiScale(settings.nextUiScale()));
        addRow(R.string.settings_preview_scale, () -> getString(R.string.settings_percent, settings.previewScalePercent), null,
                () -> change(settings.prevPreviewScale()), () -> change(settings.nextPreviewScale()));
        addRow(R.string.settings_animations_off, () -> onOff(!settings.animationsOff), this::toggleAnimations,
                this::toggleAnimations, this::toggleAnimations);
    }

    /** Order top→bottom: dim → type → note → [category, only when ART] → launch (Point B #1). */
    private void buildScreensaver() {
        addRow(R.string.settings_dim_delay, () -> delayText(settings.dimDelaySec, R.string.settings_seconds), null,
                () -> change(settings.prevDimDelay()), () -> change(settings.nextDimDelay()));
        addRow(R.string.settings_screensaver_type, this::typeText, null,
                () -> changeType(settings.prevScreensaverType()), () -> changeType(settings.nextScreensaverType()));
        videoNote = addNote(getString(R.string.settings_screensaver_hint));
        if (settings.screensaverType == LauncherSettings.ScreensaverType.ART) {
            addArtCategoryRow();
        }
        addRow(R.string.settings_screensaver_delay,
                () -> delayText(settings.screensaverDelayMin, R.string.settings_minutes), null,
                () -> changeScreensaverDelay(settings.prevScreensaverDelay()),
                () -> changeScreensaverDelay(settings.nextScreensaverDelay()));
        updateVideoNote();
    }

    /** The «Категория» row: cycles the static {@link WallhavenCategory} list — always cyclable, no loading/empty
     * gate (plan B2: the catalog is fixed at compile time, no network/disk load). */
    private void addArtCategoryRow() {
        addRow(R.string.settings_art_category, this::artCategoryText, null,
                () -> cycleArtPreset(-1), () -> cycleArtPreset(1));
    }

    private void buildOptimization() {
        addRow(R.string.settings_cleanup, null,
                () -> startActivity(new Intent(this, SystemCleanupActivity.class)), null, null);
    }

    private void showSearch() {
        page = Page.SEARCH;
        clearRows();
        tabStrip.setVisibility(View.GONE);
        topList.setVisibility(View.GONE);
        input = createInput(R.string.settings_search_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorInfo.IME_ACTION_SEARCH, this::search);
        addRow(R.string.settings_city, input, null, this::startEditing, null, null);
        addRow(R.string.settings_search_go, null, () -> search(), null, null);
        resultsStart = rows.size();
        nav = new State(Zone.LIST, nav.tab, 0);
        status("");
        applyFocusAfterLayout(false);
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
        videoNote = null;
        topRows.clear();
        topList.removeAllViews();
        rows.clear();
        list.removeAllViews();
    }

    private Row addRow(int label, Supplier<String> valueText, Runnable ok, Runnable left, Runnable right) {
        return addRow(label, text("", R.color.text_secondary, R.font.outfit_light), valueText, ok, left, right);
    }

    private Row addRow(int label, TextView value, Supplier<String> valueText, Runnable ok, Runnable left, Runnable right) {
        Row row = new Row(getString(label), value, valueText, ok, left, right);
        addRowView(row);
        return row;
    }

    private void addRowView(Row row) {
        addRowView(row, list, rows);
    }

    private void addRowView(Row row, LinearLayout parent, List<Row> into) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.bottomMargin = dp(4);
        parent.addView(row.view, lp);
        into.add(row);
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

    // ---------------------------------------------------------------- rights

    private String rightsText(List<RequiredRights.Right> missing) {
        if (rightsRunning) return getString(R.string.settings_rights_running);
        if (!rightsError.isEmpty()) return rightsError;
        StringBuilder sb = new StringBuilder();
        for (RequiredRights.Right r : RequiredRights.withoutHome(missing)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(getString(rightName(r)));
        }
        return sb.toString();
    }

    private static int rightName(RequiredRights.Right r) {
        switch (r) {
            case FINE_LOCATION: return R.string.settings_right_location;
            case TV_LISTINGS: return R.string.settings_right_listings;
            case SECURE_SETTINGS: return R.string.settings_right_secure;
            case USAGE_STATS: return R.string.settings_right_usage;
            default: return R.string.settings_right_home;
        }
    }

    private String homeText() {
        if (homeRunning) return getString(R.string.settings_rights_running);
        return homeError;
    }

    private String errorText(RightsFixer.Outcome o) {
        if (o.errors.isEmpty()) return "";
        ShellError kind = o.firstError();
        return kind == ShellError.UNAUTHORIZED ? getString(R.string.settings_rights_unauthorized)
                : getString(R.string.settings_rights_failed, kind == null ? "?" : kind.name());
    }

    private void grantRights() {
        if (rightsRunning) return;
        rightsRunning = true;
        rightsError = "";
        refreshValues();
        fixer.grantMissing(o -> {
            rightsRunning = false;
            if (isDestroyed()) return;
            rightsError = errorText(o);
            refreshRights();
        });
    }

    private void setHome() {
        if (homeRunning) return;
        homeRunning = true;
        homeError = "";
        refreshValues();
        fixer.setHome(o -> {
            homeRunning = false;
            if (isDestroyed()) return;
            homeError = errorText(o);
            refreshRights();
        });
    }

    /** Rebuilds the list when a warning row appeared or disappeared; otherwise only refreshes the values. */
    private void refreshRights() {
        if (page != Page.MAIN || rows.isEmpty()) return;
        List<RequiredRights.Right> missing = RequiredRights.missing(rightsProbe);
        boolean needRights = !RequiredRights.withoutHome(missing).isEmpty();
        boolean needHome = missing.contains(RequiredRights.Right.HOME);
        if (needRights == (rightsRow >= 0) && needHome == (homeRow >= 0)) {
            refreshValues();
            return;
        }
        showMain(nav);
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
        switch (settings.screensaverType) {
            case NASA_VIDEO: return getString(R.string.settings_type_video);
            case ART: return getString(R.string.settings_type_art);
            default: return getString(R.string.settings_type_starfield);
        }
    }

    /**
     * Cycles the screensaver type. The category row exists only for ART, so when ART-ness flips the list is rebuilt
     * (the {@link #refreshRights()} precedent); {@code nav} keeps D-pad focus on the type row across the rebuild. A
     * step that does not cross the ART boundary only refreshes the note.
     */
    private void changeType(LauncherSettings next) {
        boolean wasArt = settings.screensaverType == LauncherSettings.ScreensaverType.ART;
        change(next); // overwrites settings and refreshes the current rows
        boolean isArt = settings.screensaverType == LauncherSettings.ScreensaverType.ART;
        if (wasArt != isArt) {
            showMain(nav);
        } else {
            updateVideoNote();
        }
    }

    /**
     * The note directly under the type row (named {@code updateVideoNote} historically; now covers every type): the
     * generic screensaver hint for STARFIELD and ART alike (the ART category list is static — no loading/unavailable
     * state to report); the NASA streaming credit for NASA_VIDEO.
     */
    private void updateVideoNote() {
        if (videoNote == null || page != Page.MAIN) return;
        switch (settings.screensaverType) {
            case NASA_VIDEO:
                videoNote.setText(getString(R.string.settings_video_credit, getString(R.string.nasa_credit)));
                break;
            default:
                videoNote.setText(R.string.settings_screensaver_hint);
                break;
        }
    }

    // ---- ART category row ----

    private String artCategoryText() {
        return getString(categoryLabelRes(WallhavenCategory.effective(settings.artPreset.key)));
    }

    private static int categoryLabelRes(WallhavenCategory category) {
        switch (category) {
            case LANDSCAPES: return R.string.settings_art_landscapes;
            case SPACE_SKY: return R.string.settings_art_space_sky;
            case CITY: return R.string.settings_art_city;
            case ABSTRACT: return R.string.settings_art_abstract;
            case MINIMAL: return R.string.settings_art_minimal;
            default: return R.string.settings_art_nature;
        }
    }

    private void cycleArtPreset(int step) {
        WallhavenCategory current = WallhavenCategory.effective(settings.artPreset.key);
        WallhavenCategory next = WallhavenCategory.cycle(current, step);
        if (next != current) change(settings.withArtPreset(next)); // the only path that persists a category
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

    private void toggleAnimations() {
        change(settings.withAnimationsOff(!settings.animationsOff));
    }

    /** Delay 0 is «Выкл». */
    private String delayText(int value, int unitRes) {
        return value == 0 ? getString(R.string.settings_off) : getString(unitRes, value);
    }

    /** The system dream follows the launcher screensaver delay: enabled for any delay, off for 0. */
    private void changeScreensaverDelay(LauncherSettings next) {
        change(next);
        status(SystemScreensaver.sync(this, next) ? "" : getString(R.string.settings_screensaver_denied));
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
        if (!places.isEmpty()) nav = new State(Zone.LIST, nav.tab, resultsStart);
        applyFocusAfterLayout(true);
    }

    private void pick(GeocodingModel.Place place) {
        weatherPrefs.saveLocation(place.toLocation());
        showMain(new State(Zone.LIST, nav.tab, -1));
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
        for (int i = 0; i < topRows.size(); i++) topRows.get(i).refresh(nav.zone == Zone.TOP_ROWS && i == nav.row);
        for (int i = 0; i < rows.size(); i++) rows.get(i).refresh(nav.zone == Zone.LIST && i == nav.row);
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

    private void applyFocus(boolean requestedAnimate) {
        boolean animate = motion.focusAnimated(requestedAnimate);
        for (int i = 0; i < topRows.size(); i++) {
            Row r = topRows.get(i);
            boolean on = nav.zone == Zone.TOP_ROWS && i == nav.row;
            r.refresh(on);
            FocusDim.applyAlpha(r.view, FocusDim.Role.TEXT, on, animate);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean on = nav.zone == Zone.LIST && i == nav.row;
            r.refresh(on);
            FocusDim.applyAlpha(r.view, FocusDim.Role.TEXT, on, animate);
        }
        if (page == Page.MAIN) {
            for (int i = 0; i < tabViews.size(); i++) {
                tabViews.get(i).setTextColor(getColor(i == nav.tab ? R.color.text_primary : R.color.text_secondary));
            }
        }
        RectF target = new RectF();
        float radius = 12 * density;
        View v;
        if (nav.zone == Zone.TABS) {
            v = tabViews.get(nav.tab);
        } else if (nav.zone == Zone.TOP_ROWS) {
            v = topRows.get(nav.row).view;
        } else {
            v = rows.get(nav.row).view;
            int margin = dp(8);
            if (v.getTop() - margin < scroll.getScrollY()) {
                scroll.scrollTo(0, Math.max(0, v.getTop() - margin));
            } else if (v.getBottom() + margin > scroll.getScrollY() + scroll.getHeight()) {
                scroll.scrollTo(0, v.getBottom() + margin - scroll.getHeight());
            }
        }
        if (!Tiles.locate(v, root, target)) return;
        if (nav.zone == Zone.LIST) target.offset(0, -scroll.getScrollY());
        if (nav.zone == Zone.TABS) radius = Tiles.topGlass(target, density);
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
        if (editing) return super.dispatchKeyEvent(e);
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (centerKey.onKey(down, e.getRepeatCount()) == CenterKey.Press.SHORT) {
                Row row = focusedRow();
                if (row != null && row.ok != null) row.ok.run();
            }
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (e.getRepeatCount() == 0) move(Key.UP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (e.getRepeatCount() == 0) move(Key.DOWN);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (nav.zone == Zone.TABS && page == Page.MAIN) move(Key.LEFT);
                else if (focusedRow() != null) runIfSet(focusedRow().left);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (nav.zone == Zone.TABS && page == Page.MAIN) move(Key.RIGHT);
                else if (focusedRow() != null) runIfSet(focusedRow().right);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private Row focusedRow() {
        if (nav.zone == Zone.TOP_ROWS) return nav.row < topRows.size() ? topRows.get(nav.row) : null;
        if (nav.zone == Zone.LIST) return nav.row < rows.size() ? rows.get(nav.row) : null;
        return null;
    }

    private static void runIfSet(Runnable r) {
        if (r != null) r.run();
    }

    private void move(Key key) {
        State next = SettingsTabsNav.next(nav, key, topRows.size(), rows.size());
        if (next.equals(nav)) return;
        boolean tabChanged = next.tab != nav.tab;
        nav = next;
        if (tabChanged) showMain(nav);
        else applyFocus(true);
    }

    private void onBack() {
        if (editing) {
            stopEditing();
        } else if (page == Page.SEARCH) {
            showMain(new State(Zone.LIST, nav.tab, manualRow));
        } else if (SettingsTabsNav.backCloses(nav)) {
            finish();
        } else {
            nav = SettingsTabsNav.back(nav);
            applyFocus(true);
        }
    }
}
