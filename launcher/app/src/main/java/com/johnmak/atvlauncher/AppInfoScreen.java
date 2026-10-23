package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/** Opens the system app-info page of a package (uninstall, force stop, clear data live there). */
final class AppInfoScreen {
    private static final String TAG = "AppInfoScreen";

    private AppInfoScreen() {
    }

    static void open(Context context, String packageName) {
        try {
            context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            Log.w(TAG, "app info unavailable: " + packageName, ex);
        }
    }
}
