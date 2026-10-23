package com.johnmak.atvlauncher;

/**
 * Size of the feed cards («Продолжить просмотр», «Рекомендации») in percent of the base card: 70%..150% in 10% steps.
 * Independent of {@link UiScale}: the card height in dp is {@link #CARD_BASE_DP} x percent, the row around it grows
 * with it. The rules are pure; the adapter and the page shift read the same numbers.
 */
final class PreviewScale {
    static final int DEFAULT_PERCENT = 100;
    static final int[] PERCENTS = {70, 80, 90, 100, 110, 120, 130, 140, 150};
    /** Card (poster) height at 100%. */
    static final int CARD_BASE_DP = 135;
    /** Poster top inside the card frame: room for the focus scale-up. */
    static final int POSTER_TOP_DP = 7;
    /** Free space under the poster inside the row. */
    static final int ROW_BOTTOM_DP = 8;

    private PreviewScale() {
    }

    static boolean isValid(int percent) {
        return indexOf(percent) >= 0;
    }

    /** Boundary for persisted values: anything outside the list becomes the default. */
    static int fromStored(int percent) {
        return isValid(percent) ? percent : DEFAULT_PERCENT;
    }

    /** One step up; stays at the maximum. */
    static int next(int percent) {
        return PERCENTS[Math.min(PERCENTS.length - 1, indexOf(percent) + 1)];
    }

    /** One step down; stays at the minimum. */
    static int prev(int percent) {
        return PERCENTS[Math.max(0, indexOf(percent) - 1)];
    }

    /** Poster height in dp. */
    static int cardHeightDp(int percent) {
        return Math.round(CARD_BASE_DP * percent / 100f);
    }

    /** Height of a feed row in dp: poster plus its top and bottom room (150dp at 100%). */
    static int rowHeightDp(int percent) {
        return POSTER_TOP_DP + cardHeightDp(percent) + ROW_BOTTOM_DP;
    }

    private static int indexOf(int percent) {
        for (int i = 0; i < PERCENTS.length; i++) if (PERCENTS[i] == percent) return i;
        return -1;
    }
}
