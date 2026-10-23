package com.johnmak.atvlauncher;

/** Immutable launcher settings (screensaver, dimming, VPN button, UI scale, preview scale, animations) with cycling helpers for the option lists. */
final class LauncherSettings {
    /** What the screensaver shows. NASA_VIDEO is streamed; the starfield shows until the first frame and whenever the stream fails (see {@link ScreensaverKind}). */
    enum ScreensaverType { STARFIELD, NASA_VIDEO }

    /** Delay 0 = the stage is off (first option of each list). */
    static final int[] SCREENSAVER_DELAYS_MIN = {0, 1, 2, 3, 5, 10};
    static final int[] DIM_DELAYS_SEC = {0, 15, 30, 60, 120};

    static final LauncherSettings DEFAULTS =
            new LauncherSettings(2, ScreensaverType.STARFIELD, 30, true, "", UiScale.DEFAULT_PERCENT,
                    PreviewScale.DEFAULT_PERCENT, false);

    final int screensaverDelayMin;
    final ScreensaverType screensaverType;
    final int dimDelaySec;
    final boolean vpnButtonVisible;
    /** Chosen VPN app package; empty = not chosen yet (resolved by {@link VpnApps#choose}). */
    final String vpnPackage;
    /** UI scale in percent, one of {@link UiScale#PERCENTS}. */
    final int uiScalePercent;
    /** Feed card size in percent, one of {@link PreviewScale#PERCENTS}. */
    final int previewScalePercent;
    /** Animations switched off (storage only here; behaviour is wired elsewhere). */
    final boolean animationsOff;

    LauncherSettings(int screensaverDelayMin, ScreensaverType screensaverType, int dimDelaySec,
            boolean vpnButtonVisible, String vpnPackage, int uiScalePercent, int previewScalePercent,
            boolean animationsOff) {
        if (indexOf(SCREENSAVER_DELAYS_MIN, screensaverDelayMin) < 0) {
            throw new IllegalArgumentException("screensaver delay: " + screensaverDelayMin);
        }
        if (indexOf(DIM_DELAYS_SEC, dimDelaySec) < 0) throw new IllegalArgumentException("dim delay: " + dimDelaySec);
        if (screensaverType == null) throw new IllegalArgumentException("screensaver type");
        if (vpnPackage == null) throw new IllegalArgumentException("vpn package");
        if (!UiScale.isValid(uiScalePercent)) throw new IllegalArgumentException("ui scale: " + uiScalePercent);
        if (!PreviewScale.isValid(previewScalePercent)) {
            throw new IllegalArgumentException("preview scale: " + previewScalePercent);
        }
        this.screensaverDelayMin = screensaverDelayMin;
        this.screensaverType = screensaverType;
        this.dimDelaySec = dimDelaySec;
        this.vpnButtonVisible = vpnButtonVisible;
        this.vpnPackage = vpnPackage;
        this.uiScalePercent = uiScalePercent;
        this.previewScalePercent = previewScalePercent;
        this.animationsOff = animationsOff;
    }

    /**
     * Boundary for persisted values: a delay outside its option list, an unknown type name (edited or older
     * data) or a missing package becomes the default. One-time migration: the legacy «enabled» flags of the
     * old format turn a disabled stage into delay 0 (Off); pass {@code true} when no legacy flag is stored.
     */
    static LauncherSettings fromStored(boolean legacyScreensaverEnabled, int screensaverDelayMin, String screensaverType,
            boolean legacyDimEnabled, int dimDelaySec, boolean vpnButtonVisible, String vpnPackage, int uiScalePercent,
            int previewScalePercent, boolean animationsOff) {
        int saver = indexOf(SCREENSAVER_DELAYS_MIN, screensaverDelayMin) < 0 ? DEFAULTS.screensaverDelayMin : screensaverDelayMin;
        int dim = indexOf(DIM_DELAYS_SEC, dimDelaySec) < 0 ? DEFAULTS.dimDelaySec : dimDelaySec;
        return new LauncherSettings(
                legacyScreensaverEnabled ? saver : 0,
                typeOf(screensaverType),
                legacyDimEnabled ? dim : 0,
                vpnButtonVisible,
                vpnPackage == null ? DEFAULTS.vpnPackage : vpnPackage,
                UiScale.fromStored(uiScalePercent),
                PreviewScale.fromStored(previewScalePercent),
                animationsOff);
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

    LauncherSettings withAnimationsOff(boolean v) {
        return new LauncherSettings(screensaverDelayMin, screensaverType, dimDelaySec, vpnButtonVisible, vpnPackage,
                uiScalePercent, previewScalePercent, v);
    }

    LauncherSettings withVpnButtonVisible(boolean v) {
        return new LauncherSettings(screensaverDelayMin, screensaverType, dimDelaySec, v, vpnPackage, uiScalePercent, previewScalePercent,
                animationsOff);
    }

    LauncherSettings withVpnPackage(String pkg) {
        return new LauncherSettings(screensaverDelayMin, screensaverType, dimDelaySec, vpnButtonVisible, pkg, uiScalePercent, previewScalePercent,
                animationsOff);
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
        return new LauncherSettings(screensaverDelayMin, screensaverType, dimDelaySec, vpnButtonVisible, vpnPackage, percent, previewScalePercent,
                animationsOff);
    }

    /** One 10% step up; stays at 150%. */
    LauncherSettings nextPreviewScale() {
        return withPreviewScale(PreviewScale.next(previewScalePercent));
    }

    /** One 10% step down; stays at 70%. */
    LauncherSettings prevPreviewScale() {
        return withPreviewScale(PreviewScale.prev(previewScalePercent));
    }

    private LauncherSettings withPreviewScale(int percent) {
        return new LauncherSettings(screensaverDelayMin, screensaverType, dimDelaySec, vpnButtonVisible, vpnPackage, uiScalePercent, percent,
                animationsOff);
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
        return new LauncherSettings(screensaverDelayMin, t, dimDelaySec, vpnButtonVisible, vpnPackage, uiScalePercent, previewScalePercent,
                animationsOff);
    }

    private LauncherSettings withScreensaverDelay(int min) {
        return new LauncherSettings(min, screensaverType, dimDelaySec, vpnButtonVisible, vpnPackage, uiScalePercent, previewScalePercent,
                animationsOff);
    }

    private LauncherSettings withDimDelay(int sec) {
        return new LauncherSettings(screensaverDelayMin, screensaverType, sec, vpnButtonVisible, vpnPackage, uiScalePercent, previewScalePercent,
                animationsOff);
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
