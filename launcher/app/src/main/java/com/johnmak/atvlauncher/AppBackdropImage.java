package com.johnmak.atvlauncher;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;

/** Best available picture of an app for the page backdrop: leanback banner, else the launcher icon at the highest density. */
final class AppBackdropImage {
    private static final String TAG = "AtvBackdrop";
    private static final int MAX_SIDE = 256;

    private final PackageManager pm;

    AppBackdropImage(PackageManager pm) {
        this.pm = pm;
    }

    /** Blocking; worker thread only. Returns null when the app is unknown or cannot be read (e.g. just uninstalled). */
    Bitmap load(String packageName) {
        AppLoader.Result apps = AppCache.apps();
        AppEntry entry = apps == null ? null : apps.byPackage.get(packageName);
        return entry == null ? null : load(entry.launchIntent.getComponent());
    }

    private Bitmap load(ComponentName component) {
        try {
            ActivityInfo info = pm.getActivityInfo(component, 0);
            Drawable banner = info.loadBanner(pm);
            return render(banner != null ? banner : icon(info));
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            Log.w(TAG, "no backdrop image for " + component.flattenToShortString() + " (" + ex + ")");
            return null;
        }
    }

    private Drawable icon(ActivityInfo info) throws PackageManager.NameNotFoundException {
        int res = info.getIconResource();
        if (res == 0) return pm.getDefaultActivityIcon();
        return pm.getResourcesForApplication(info.applicationInfo)
                .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
    }

    /** Draws the drawable at its intrinsic size, capped to {@link #MAX_SIDE} on the long side. */
    private static Bitmap render(Drawable d) {
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
        int bw = Math.max(1, Math.round(w * scale));
        int bh = Math.max(1, Math.round(h * scale));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(bmp));
        return bmp;
    }
}
