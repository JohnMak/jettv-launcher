package com.johnmak.atvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** First start: opens Settings once, focused on the missing-rights row, when any right is missing. */
final class RightsPrompt {
    private static final String PREFS = "rights_prompt";
    private static final String KEY_SHOWN = "rights_prompt_shown";

    private RightsPrompt() {}

    /** @return true when Settings was opened. */
    static boolean maybeOpenSettings(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SHOWN, false)) return false;
        if (RequiredRights.missing(RequiredRights.android(activity)).isEmpty()) return false;
        prefs.edit().putBoolean(KEY_SHOWN, true).apply();
        activity.startActivity(new Intent(activity, LauncherSettingsActivity.class)
                .putExtra(LauncherSettingsActivity.EXTRA_FOCUS_RIGHTS, true));
        return true;
    }
}
