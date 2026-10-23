package com.johnmak.atvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Opens Settings once, focused on the missing-rights row. Never unattended: the caller invokes it on the user's first
 * key press in the activity session, and never in a process that was started by a silent update install.
 */
final class RightsPrompt {
    private static final String PREFS = "rights_prompt";
    private static final String KEY_SHOWN = "rights_prompt_shown";

    private RightsPrompt() {}

    enum Decision { NOTHING, OPEN, SKIP_AFTER_UPDATE }

    /** Pure rule. SKIP_AFTER_UPDATE: do not open, and consume the one-time prompt. */
    static Decision decide(boolean shownBefore, boolean rightsMissing, boolean startedByUpdate) {
        if (shownBefore || !rightsMissing) return Decision.NOTHING;
        return startedByUpdate ? Decision.SKIP_AFTER_UPDATE : Decision.OPEN;
    }

    /** @return true when Settings was opened. Call only after a user key press. */
    static boolean maybeOpenSettings(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Decision d = decide(prefs.getBoolean(KEY_SHOWN, false),
                !RequiredRights.missing(RequiredRights.android(activity)).isEmpty(),
                UpdateController.instance(activity).processStartedByUpdate());
        if (d == Decision.NOTHING) return false;
        prefs.edit().putBoolean(KEY_SHOWN, true).apply();
        if (d == Decision.SKIP_AFTER_UPDATE) return false;
        activity.startActivity(new Intent(activity, LauncherSettingsActivity.class)
                .putExtra(LauncherSettingsActivity.EXTRA_FOCUS_RIGHTS, true));
        return true;
    }
}
