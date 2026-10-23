package com.johnmak.atvlauncher;

/**
 * How far the home page moves up when focus is in a feed row, so the focused row and its info field are fully on the
 * screen. The dp constants mirror activity_main.xml / view_feed_info.xml / styles.xml (screen 540dp = 1080p at UI
 * scale 100 %); the height of a feed row follows the preview scale ({@link PreviewScale#rowHeightDp}).
 * FeedPageShiftTest checks that the result fits.
 */
final class FeedPageShift {
    static final int SCREEN_DP = 540;
    /** Top of the feed block on the page (activity_main.xml feed_block marginTop). */
    static final int FEED_TOP_DP = 460;
    static final int HEADING_DP = 30;
    /** Info field incl. its top margin (view_feed_info.xml). */
    static final int INFO_DP = 94;
    /** Gap above the second heading (activity_main.xml mixed_heading marginTop). */
    static final int ROW_GAP_DP = 8;
    /** The first heading sits this far from the screen top while the first row is focused. */
    static final int HEADING_TOP_DP = 30;
    /** Free space kept under the focused row's info field. */
    static final int BOTTOM_MARGIN_DP = 12;

    /** Configure tile = a bare icon of the top-bar icon size (view_feed_config.xml). */
    static final int CONFIG_ICON_DP = 26;
    /** Free space between the configure icon and the first card (room for both glasses). */
    static final int CONFIG_GAP_DP = 20;
    /** Cards start this far below the top of a feed row (view_feed_config.xml used to mirror it as marginTop). */
    static final int CARD_TOP_DP = 7;

    private FeedPageShift() {}

    /** How far the row slides right to make room for the configure icon (dp). */
    static int configShiftDp() {
        return CONFIG_ICON_DP + CONFIG_GAP_DP;
    }

    /** Top margin of the configure icon (dp from the row top) that centers it on cards of the given height. */
    static float configIconTopDp(float cardHeightDp) {
        return CARD_TOP_DP + (cardHeightDp - CONFIG_ICON_DP) / 2f;
    }

    /** Bottom of the second row's info field on the page (dp from the page top) when both rows are shown. */
    static float mixedInfoBottomDp(int previewScalePercent) {
        int row = PreviewScale.rowHeightDp(previewScalePercent);
        return FEED_TOP_DP + HEADING_DP + row + INFO_DP + ROW_GAP_DP + HEADING_DP + row + INFO_DP;
    }

    /** Upward shift in dp for the focused feed row; {@code watchVisible} is whether the first row has any cards. */
    static float shiftDp(FocusModel.Zone zone, boolean watchVisible, int previewScalePercent) {
        float first = FEED_TOP_DP - HEADING_TOP_DP;
        if (zone != FocusModel.Zone.MIXED || !watchVisible) return first;
        return Math.max(first, mixedInfoBottomDp(previewScalePercent) - (SCREEN_DP - BOTTOM_MARGIN_DP));
    }
}
