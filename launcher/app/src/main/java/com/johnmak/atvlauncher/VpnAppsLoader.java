package com.johnmak.atvlauncher;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/** Installed VPN apps: packages that declare a VpnService (see {@link VpnApps#filter}). Main-thread cheap query. */
final class VpnAppsLoader {
    private VpnAppsLoader() {}

    @SuppressWarnings("deprecation")
    static List<VpnApps.App> installed(PackageManager pm) {
        List<VpnApps.Candidate> candidates = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent("android.net.VpnService"),
                PackageManager.GET_META_DATA | PackageManager.MATCH_ALL)) {
            candidates.add(new VpnApps.Candidate(r.serviceInfo.packageName,
                    r.serviceInfo.applicationInfo.loadLabel(pm).toString(), r.serviceInfo.permission));
        }
        return VpnApps.filter(candidates);
    }
}
