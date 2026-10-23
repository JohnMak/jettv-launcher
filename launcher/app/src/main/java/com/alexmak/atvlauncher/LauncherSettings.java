package com.alexmak.atvlauncher;

/** Immutable launcher settings (screensaver, dimming, VPN button, UI scale) with cycling helpers for the option lists. */
final class LauncherSettings {
    /** What the screensaver shows. NASA_VIDEO is streamed; the starfield shows until the first frame and whenever the stream fails (see {@link ScreensaverKind}). */
    enum ScreensaverType { STARFIELD, NASA_VIDEO }

    static final int[] SCREENSAVER_DELAYS_MIN = {1, 2, 3, 5, 10};
    static final int[] DIM_DELAYS_SEC = {15, 30, 60, 120};

    static final LauncherSettings DEFAULTS =
            new LauncherSettings(true, 2, ScreensaverType.STARFIELD, true, 30, true, "", UiScale.DEFAULT_PERCENT);

    final boolean screensaverEnabled;
    final int screensaverDelayMin;
    final ScreensaverType screensaverType;
    final boolean dimEnabled;
    final int dimDelaySec;
    final boolean vpnButtonVisible;
    /** Chosen VPN app package; empty = not chosen yet (resolved by {@link VpnApps#choose}). */
    final String vpnPackage;
    /** UI scale in percent, one of {@link UiScale#PERCENTS}. */
    final int uiScalePercent;

    LauncherSettings(boolean screensaverEnabled, int screensaverDelayMin, ScreensaverType screensaverType,
            boolean dimEnabled, int dimDelaySec, boolean vpnButtonVisible, String vpnPackage, int uiScalePercent) {
        if (indexOf(SCREENSAVER_DELAYS_MIN, screensaverDelayMin) < 0) {
            throw new IllegalArgumentException("screensaver delay: " + screensaverDelayMin);
        }
        if (indexOf(DIM_DELAYS_SEC, dimDelaySec) < 0) throw new IllegalArgumentException("dim delay: " + dimDelaySec);
        if (screensaverType == null) throw new IllegalArgumentException("screensaver type");
        if (vpnPackage == null) throw new IllegalArgumentException("vpn package");
        if (!UiScale.isValid(uiScalePercent)) throw new IllegalArgumentException("ui scale: " + uiScalePercent);
        this.screensaverEnabled = screensaverEnabled;
        this.screensaverDelayMin = screensaverDelayMin;
        this.screensaverType = screensaverType;
        this.dimEnabled = dimEnabled;
        this.dimDelaySec = dimDelaySec;
        this.vpnButtonVisible = vpnButtonVisible;
        this.vpnPackage = vpnPackage;
        this.uiScalePercent = uiScalePercent;
    }

    /**
     * Boundary for persisted values: a delay outside its option list, an unknown type name (edited or older
     * data) or a missing package becomes the default.
     */
    static LauncherSettings fromStored(boolean screensaverEnabled, int screensaverDelayMin, String screensaverType,
            boolean dimEnabled, int dimDelaySec, boolean vpnButtonVisible, String vpnPackage, int uiScalePercent) {
        return new LauncherSettings(screensaverEnabled,
                indexOf(SCREENSAVER_DELAYS_MIN, screensaverDelayMin) < 0 ? DEFAULTS.screensaverDelayMin : screensaverDelayMin,
                typeOf(screensaverType),
                dimEnabled,
                indexOf(DIM_DELAYS_SEC, dimDelaySec) < 0 ? DEFAULTS.dimDelaySec : dimDelaySec,
                vpnButtonVisible,
                vpnPackage == null ? DEFAULTS.vpnPackage : vpnPackage,
                UiScale.fromStored(uiScalePercent));
    }

    private static ScreensaverType typeOf(String name) {
        for (ScreensaverType t : ScreensaverType.values()) if (t.name().equals(name)) return t;
        return DEFAULTS.screensaverType;
    }

    long screensaverDelayMillis() {
        return screensaverDelayMin * 60_000L;
    }

    long dimDelayMillis() {
        return dimDelaySec * 1_000L;
    }

    LauncherSettings withScreensaverEnabled(boolean v) {
        return new LauncherSettings(v, screensaverDelayMin, screensaverType, dimEnabled, dimDelaySec,
                vpnButtonVisible, vpnPackage, uiScalePercent);
    }

    LauncherSettings withDimEnabled(boolean v) {
        return new LauncherSettings(screensaverEnabled, screensaverDelayMin, screensaverType, v, dimDelaySec,
                vpnButtonVisible, vpnPackage, uiScalePercent);
    }

    LauncherSettings withVpnButtonVisible(boolean v) {
        return new LauncherSettings(screensaverEnabled, screensaverDelayMin, screensaverType, dimEnabled, dimDelaySec,
                v, vpnPackage, uiScalePercent);
    }

    LauncherSettings withVpnPackage(String pkg) {
        return new LauncherSettings(screensaverEnabled, screensaverDelayMin, screensaverType, dimEnabled, dimDelaySec,
                vpnButtonVisible, pkg, uiScalePercent);
    }

    /** One 5% step up; stays at 130%. */
    LauncherSettings nextUiScale() {
        return withUiScale(UiScale.next(uiScalePercent));
    }

    /** One 5% step down; stays at 70%. */
    LauncherSettings prevUiScale() {
        return withUiScale(UiScale.prev(uiScalePercent));
    }

    private LauncherSettings withUiScale(int percent) {
        return new LauncherSettings(screensaverEnabled, screensaverDelayMin, screensaverType, dimEnabled, dimDelaySec,
                vpnButtonVisible, vpnPackage, percent);
    }

    /** Two types: next and previous are the same switch. */
    LauncherSettings nextScreensaverType() {
        ScreensaverType[] all = ScreensaverType.values();
        return withScreensaverType(all[(screensaverType.ordinal() + 1) % all.length]);
    }

    LauncherSettings prevScreensaverType() {
        ScreensaverType[] all = ScreensaverType.values();
        return withScreensaverType(all[(screensaverType.ordinal() + all.length - 1) % all.length]);
    }

    LauncherSettings nextScreensaverDelay() {
        return withScreensaverDelay(cycle(SCREENSAVER_DELAYS_MIN, screensaverDelayMin, 1));
    }

    LauncherSettings prevScreensaverDelay() {
        return withScreensaverDelay(cycle(SCREENSAVER_DELAYS_MIN, screensaverDelayMin, -1));
    }

    LauncherSettings nextDimDelay() {
        return withDimDelay(cycle(DIM_DELAYS_SEC, dimDelaySec, 1));
    }

    LauncherSettings prevDimDelay() {
        return withDimDelay(cycle(DIM_DELAYS_SEC, dimDelaySec, -1));
    }

    private LauncherSettings withScreensaverType(ScreensaverType t) {
        return new LauncherSettings(screensaverEnabled, screensaverDelayMin, t, dimEnabled, dimDelaySec,
                vpnButtonVisible, vpnPackage, uiScalePercent);
    }

    private LauncherSettings withScreensaverDelay(int min) {
        return new LauncherSettings(screensaverEnabled, min, screensaverType, dimEnabled, dimDelaySec,
                vpnButtonVisible, vpnPackage, uiScalePercent);
    }

    private LauncherSettings withDimDelay(int sec) {
        return new LauncherSettings(screensaverEnabled, screensaverDelayMin, screensaverType, dimEnabled, sec,
                vpnButtonVisible, vpnPackage, uiScalePercent);
    }

    private static int cycle(int[] options, int current, int step) {
        int i = indexOf(options, current);
        return options[(i + step + options.length) % options.length];
    }

    private static int indexOf(int[] options, int value) {
        for (int i = 0; i < options.length; i++) if (options[i] == value) return i;
        return -1;
    }
}
