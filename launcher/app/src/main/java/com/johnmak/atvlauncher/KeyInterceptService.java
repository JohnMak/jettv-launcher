package com.johnmak.atvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Resources;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional remote-key interceptor. Does nothing until the user enables it in system accessibility
 * settings and config.xml flags are switched on.
 */
public final class KeyInterceptService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // key filtering only
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Resources res = getResources();
        int code = event.getKeyCode();
        Runnable action = null;

        if (res.getBoolean(R.bool.HOME_KEY_SERVICE) && code == KeyEvent.KEYCODE_HOME) {
            action = this::openLauncher;
        } else if (res.getBoolean(R.bool.KEY_REMAP_SERVICE)) {
            if (contains(res.getIntArray(R.array.settings_key_keycodes), code)) {
                action = () -> startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                int[] codes = res.getIntArray(R.array.app_key_keycodes);
                String[] packages = res.getStringArray(R.array.app_key_packages);
                for (int i = 0; i < codes.length && i < packages.length; i++) {
                    if (codes[i] == code) {
                        final String pkg = packages[i];
                        action = () -> openPackage(pkg);
                        break;
                    }
                }
            }
        }
        if (action == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) action.run();
        return true;
    }

    private void openLauncher() {
        startActivity(launcherIntent());
    }

    private Intent launcherIntent() {
        return new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void openPackage(String pkg) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

}
