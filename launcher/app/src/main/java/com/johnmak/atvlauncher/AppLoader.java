package com.johnmak.atvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Queries launchable apps and renders their tiles. Background threads only; loads are serialized.
 * Entries are cached by component + install/update time, so tiles are computed once.
 */
final class AppLoader {
    static final int TILE_W = 352;
    static final int ICON_SIZE = 48;
    static final int TILE_H = 198;

    static final class Result {
        /** Launchable apps (self excluded), sorted by label. */
        final List<AppEntry> apps;
        final Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        final String signature;

        Result(List<AppEntry> apps) {
            this.apps = apps;
            StringBuilder sb = new StringBuilder();
            for (AppEntry e : apps) {
                byPackage.put(e.packageName, e);
                sb.append(e.packageName).append(e.label).append('|');
            }
            this.signature = sb.toString();
        }
    }

    private final Context context;
    private final PackageManager pm;
    private final Map<String, AppEntry> cache = new HashMap<>();

    AppLoader(Context context) {
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    synchronized Result load() {
        // One resolve info per package; leanback activity wins over the plain launcher one.
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        Map<String, String> categoryByPackage = new HashMap<>();
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, byPackage, categoryByPackage);
        collect(Intent.CATEGORY_LAUNCHER, byPackage, categoryByPackage);
        byPackage.remove(context.getPackageName());

        List<AppEntry> apps = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : byPackage.entrySet()) {
            apps.add(entry(e.getValue(), categoryByPackage.get(e.getKey())));
        }
        final Locale locale = Locale.getDefault();
        apps.sort(Comparator.comparing((AppEntry a) -> a.label.toLowerCase(locale)));
        return new Result(apps);
    }

    private void collect(String category, Map<String, ResolveInfo> out, Map<String, String> categories) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!out.containsKey(pkg)) {
                out.put(pkg, ri);
                categories.put(pkg, category);
            }
        }
    }

    private AppEntry entry(ResolveInfo ri, String category) {
        String pkg = ri.activityInfo.packageName;
        long updated = 0;
        try {
            updated = pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
            // package vanished between query and lookup; next refresh drops it
        }
        String key = pkg + '/' + ri.activityInfo.name + '@' + updated;
        AppEntry cached = cache.get(key);
        if (cached != null) return cached;

        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        String label = String.valueOf(ri.loadLabel(pm));
        Bitmap tile = renderTile(ri, pkg);
        AppEntry fresh = new AppEntry(pkg, label, launch, tile, renderIcon(ri));
        cache.put(key, fresh);
        return fresh;
    }

    /** Banner (activity, then application) centre-cropped; otherwise the icon on a coloured tile. */
    private Bitmap renderTile(ResolveInfo ri, String pkg) {
        Bitmap bmp = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner != null && banner.getIntrinsicWidth() > 0 && banner.getIntrinsicHeight() > 0) {
            canvas.drawColor(Color.BLACK);
            float scale = Math.max((float) TILE_W / banner.getIntrinsicWidth(),
                    (float) TILE_H / banner.getIntrinsicHeight());
            int w = Math.round(banner.getIntrinsicWidth() * scale);
            int h = Math.round(banner.getIntrinsicHeight() * scale);
            int l = (TILE_W - w) / 2;
            int t = (TILE_H - h) / 2;
            banner.setBounds(l, t, l + w, t + h);
            banner.draw(canvas);
            return bmp;
        }
        float hue = Math.abs(pkg.hashCode() % 360);
        canvas.drawColor(Color.HSVToColor(new float[]{hue, 0.45f, 0.38f}));
        Drawable icon = ri.loadIcon(pm);
        int size = 112;
        int l = (TILE_W - size) / 2;
        int t = (TILE_H - size) / 2;
        icon.setBounds(l, t, l + size, t + size);
        icon.draw(canvas);
        return bmp;
    }

    private Bitmap renderIcon(ResolveInfo ri) {
        Bitmap bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Drawable icon = ri.loadIcon(pm);
        icon.setBounds(0, 0, ICON_SIZE, ICON_SIZE);
        icon.draw(new Canvas(bmp));
        return bmp;
    }
}
