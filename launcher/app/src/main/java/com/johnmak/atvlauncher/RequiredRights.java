package com.johnmak.atvlauncher;

import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The rights the launcher needs on this device and which of them are still missing (pure; the probe is injected). */
final class RequiredRights {
    enum Right { FINE_LOCATION, TV_LISTINGS, SECURE_SETTINGS, USAGE_STATS, HOME }

    interface Probe {
        boolean hasPermission(ShellCommand.GrantablePermission permission);

        boolean usageStatsAllowed();

        boolean holdsHome();
    }

    private RequiredRights() {}

    /** Missing rights in {@link Right} order. */
    static List<Right> missing(Probe probe) {
        List<Right> out = new ArrayList<>();
        if (!probe.hasPermission(ShellCommand.GrantablePermission.ACCESS_FINE_LOCATION)) out.add(Right.FINE_LOCATION);
        if (!probe.hasPermission(ShellCommand.GrantablePermission.READ_TV_LISTINGS)) out.add(Right.TV_LISTINGS);
        if (!probe.hasPermission(ShellCommand.GrantablePermission.WRITE_SECURE_SETTINGS)) out.add(Right.SECURE_SETTINGS);
        if (!probe.usageStatsAllowed()) out.add(Right.USAGE_STATS);
        if (!probe.holdsHome()) out.add(Right.HOME);
        return out;
    }

    /** The grantable subset: everything but HOME. */
    static List<Right> withoutHome(List<Right> rights) {
        List<Right> out = new ArrayList<>(rights);
        out.remove(Right.HOME);
        return Collections.unmodifiableList(out);
    }

    static Probe android(Context context) {
        Context app = context.getApplicationContext();
        return new Probe() {
            @Override public boolean hasPermission(ShellCommand.GrantablePermission p) {
                return app.checkSelfPermission("android.permission." + p.name()) == PackageManager.PERMISSION_GRANTED;
            }

            @Override public boolean usageStatsAllowed() {
                AppOpsManager ops = app.getSystemService(AppOpsManager.class);
                return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                        app.getPackageName()) == AppOpsManager.MODE_ALLOWED;
            }

            @Override public boolean holdsHome() {
                RoleManager roles = app.getSystemService(RoleManager.class);
                return roles.isRoleHeld(RoleManager.ROLE_HOME);
            }
        };
    }
}
