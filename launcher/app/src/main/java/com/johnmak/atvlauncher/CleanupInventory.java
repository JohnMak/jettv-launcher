package com.johnmak.atvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android adapter of the cleanup screen: reads the package inventory and the bundled profiles. Blocking, worker thread only. */
final class CleanupInventory {
    private static final String TAG = "AtvCleanup";

    private CleanupInventory() {}

    static final class Snapshot {
        final List<CleanupSections.App> apps;
        /** Base set plus IME, screensaver, accessibility services, WebView and persistent apps (HOME handlers are added by the sections model). */
        final ProtectedPackages protectedSet;
        final boolean holdsHome;

        Snapshot(List<CleanupSections.App> apps, ProtectedPackages protectedSet, boolean holdsHome) {
            this.apps = apps;
            this.protectedSet = protectedSet;
            this.holdsHome = holdsHome;
        }
    }

    @SuppressWarnings("deprecation") // the flags overload of getInstalledApplications is the only one on API 30
    static Snapshot load(Context context, RequiredRights.Probe rights) {
        PackageManager pm = context.getPackageManager();
        Set<String> homes = homeHandlers(pm, context.getPackageName());
        List<ApplicationInfo> infos = pm.getInstalledApplications(
                PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
        List<CleanupSections.App> apps = new ArrayList<>(infos.size());
        for (ApplicationInfo ai : infos) {
            boolean disabledUser = false;
            try {
                disabledUser = pm.getApplicationEnabledSetting(ai.packageName)
                        == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "no enabled setting for " + ai.packageName);
            }
            CleanupPlanner.PackageState state = new CleanupPlanner.PackageState(
                    (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0, disabledUser,
                    (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                    (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0);
            apps.add(new CleanupSections.App(ai.packageName, String.valueOf(ai.loadLabel(pm)), state,
                    homes.contains(ai.packageName)));
        }
        Set<String> extra = new HashSet<>();
        String ime = currentImePackage(context);
        if (ime != null) extra.add(ime);
        extra.addAll(componentPackages(Settings.Secure.getString(context.getContentResolver(), "screensaver_components"), ','));
        extra.addAll(componentPackages(Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), ':'));
        PackageInfo webView = WebView.getCurrentWebViewPackage();
        if (webView != null) extra.add(webView.packageName);
        for (CleanupSections.App a : apps) if (a.state.persistent) extra.add(a.pkg);
        return new Snapshot(apps, ProtectedPackages.base().with(extra), rights.holdsHome());
    }

    private static Set<String> homeHandlers(PackageManager pm, String own) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        Set<String> out = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (ri.activityInfo != null && !own.equals(ri.activityInfo.packageName)) out.add(ri.activityInfo.packageName);
        }
        return out;
    }

    /** Packages of the flattened components in {@code flat} separated by {@code sep}; blank or malformed entries are skipped. */
    private static Set<String> componentPackages(String flat, char sep) {
        Set<String> out = new HashSet<>();
        if (flat == null) return out;
        for (String part : flat.split(String.valueOf(sep))) {
            ComponentName c = ComponentName.unflattenFromString(part.trim());
            if (c != null) out.add(c.getPackageName());
        }
        return out;
    }

    /** Package of the default input method, or null when none is set. */
    private static String currentImePackage(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName c = flat == null ? null : ComponentName.unflattenFromString(flat);
        return c == null ? null : c.getPackageName();
    }

    /** All valid bundled profiles (tools/debloat/*.txt); unreadable or invalid ones are logged and skipped. */
    static List<CleanupProfile> profiles(Context context) {
        List<CleanupProfile> out = new ArrayList<>();
        String[] names;
        try {
            names = context.getAssets().list("");
        } catch (IOException e) {
            Log.w(TAG, "cannot list assets: " + e.getMessage());
            return Collections.emptyList();
        }
        if (names == null) return Collections.emptyList();
        for (String n : names) {
            if (!n.endsWith(".txt")) continue;
            try (InputStream in = context.getAssets().open(n)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int r;
                while ((r = in.read(chunk)) > 0) buf.write(chunk, 0, r);
                FetchResult<CleanupProfile> p = CleanupProfile.parse(n.substring(0, n.length() - 4),
                        new String(buf.toByteArray(), StandardCharsets.UTF_8));
                if (p.isOk()) out.add(p.value());
                else Log.w(TAG, "skipping profile: " + p.error());
            } catch (IOException e) {
                Log.w(TAG, "cannot read " + n + ": " + e.getMessage());
            }
        }
        return out;
    }
}
