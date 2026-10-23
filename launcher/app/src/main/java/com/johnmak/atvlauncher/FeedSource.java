package com.johnmak.atvlauncher;

/** The two feed rows whose publishing apps the user can pick; each has its own set of excluded packages. */
enum FeedSource {
    /** «Рекомендации»: preview programs. */
    RECOMMENDATIONS("feed_excluded_recommendations", R.string.sources_title_recommendations),
    /** «Продолжить просмотр»: watch-next programs. */
    CONTINUE("feed_excluded_continue", R.string.sources_title_continue);

    /** Key of the excluded-packages set in {@link LauncherSettingsStore}. */
    final String prefsKey;
    final int titleRes;

    FeedSource(String prefsKey, int titleRes) {
        this.prefsKey = prefsKey;
        this.titleRes = titleRes;
    }
}
