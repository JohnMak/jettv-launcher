package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class ShellCommandTest {
    @Test
    public void onlyTheWhitelistedVerbsExist() {
        assertEquals(Arrays.asList("GET_DLP_LUMENS_LEVEL", "GET_BRIGHTNESS", "GET_CONTRAST",
                        "SET_DLP_LUMENS_LEVEL", "SET_BRIGHTNESS", "SET_CONTRAST",
                        "PM_GRANT", "APPOPS_ALLOW_USAGE_STATS", "ROLE_SET_HOME", "PM_DISABLE", "PM_ENABLE",
                        "PM_REMOVE_FOR_USER", "PM_RESTORE", "PM_UNINSTALL", "PM_UNINSTALL_UPDATES"),
                Arrays.stream(ShellCommand.Verb.values()).map(Enum::name).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    public void gettersBuildExactShellLines() {
        assertEquals("gmpfUnit getDlpLumensLevel", ShellCommand.Gmpf.getLevel().shellLine());
        assertEquals("gmpfUnit getBrightness 0", ShellCommand.Gmpf.getBrightness().shellLine());
        assertEquals("gmpfUnit getContrast 0", ShellCommand.Gmpf.getContrast().shellLine());
        assertEquals(ShellCommand.Verb.GET_CONTRAST, ShellCommand.Gmpf.getContrast().verb());
    }

    @Test
    public void settersBuildExactShellLines() {
        for (int v = LampLevel.MIN_LEVEL; v <= LampLevel.MAX_LEVEL; v++) {
            assertEquals("gmpfUnit setDlpLumensLevel " + v, ShellCommand.Gmpf.setLevel(LampLevel.of(v).value()).shellLine());
        }
        assertEquals("gmpfUnit setBrightness 0 0", ShellCommand.Gmpf.setBrightness(0).value().shellLine());
        assertEquals("gmpfUnit setBrightness 0 57", ShellCommand.Gmpf.setBrightness(57).value().shellLine());
        assertEquals("gmpfUnit setContrast 0 100", ShellCommand.Gmpf.setContrast(100).value().shellLine());
        assertEquals(ShellCommand.Verb.SET_BRIGHTNESS, ShellCommand.Gmpf.setBrightness(1).value().verb());
    }

    @Test
    public void outOfRangePictureValuesAreRejectedAsValues() {
        for (int bad : new int[] {-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertFalse("brightness " + bad, ShellCommand.Gmpf.setBrightness(bad).isOk());
            assertFalse("contrast " + bad, ShellCommand.Gmpf.setContrast(bad).isOk());
            assertEquals(ShellError.COMMAND_FAILED, ShellError.of(ShellCommand.Gmpf.setBrightness(bad)));
        }
        assertTrue(ShellCommand.Gmpf.setContrast(0).isOk());
    }

    @Test
    public void lampLevelRangeIsEnforcedByTheLevelType() {
        assertFalse(LampLevel.of(0).isOk());
        assertFalse(LampLevel.of(11).isOk());
    }

    @Test
    public void packageManagementBuildsExactShellLines() {
        PackageName own = PackageName.parse("com.johnmak.atvlauncher").value();
        PackageName pkg = PackageName.parse("com.google.android.youtube.tv").value();
        assertEquals("pm grant com.johnmak.atvlauncher android.permission.ACCESS_FINE_LOCATION",
                ShellCommand.Pm.grant(own, ShellCommand.GrantablePermission.ACCESS_FINE_LOCATION).shellLine());
        assertEquals("pm grant com.johnmak.atvlauncher android.permission.READ_TV_LISTINGS",
                ShellCommand.Pm.grant(own, ShellCommand.GrantablePermission.READ_TV_LISTINGS).shellLine());
        assertEquals("pm grant com.johnmak.atvlauncher android.permission.WRITE_SECURE_SETTINGS",
                ShellCommand.Pm.grant(own, ShellCommand.GrantablePermission.WRITE_SECURE_SETTINGS).shellLine());
        assertEquals("appops set com.johnmak.atvlauncher GET_USAGE_STATS allow",
                ShellCommand.Pm.allowUsageStats(own).shellLine());
        assertEquals("cmd role add-role-holder --user 0 android.app.role.HOME com.johnmak.atvlauncher",
                ShellCommand.Pm.setHome(own).shellLine());
        assertEquals("pm disable-user --user 0 com.google.android.youtube.tv", ShellCommand.Pm.disable(pkg).shellLine());
        assertEquals("pm enable --user 0 com.google.android.youtube.tv", ShellCommand.Pm.enable(pkg).shellLine());
        assertEquals("pm uninstall -k --user 0 com.google.android.youtube.tv", ShellCommand.Pm.removeForUser(pkg).shellLine());
        assertEquals("cmd package install-existing --user 0 com.google.android.youtube.tv",
                ShellCommand.Pm.restore(pkg).shellLine());
        assertEquals("pm uninstall com.google.android.youtube.tv", ShellCommand.Pm.uninstall(pkg).shellLine());
        assertEquals("pm uninstall com.google.android.youtube.tv", ShellCommand.Pm.uninstallUpdates(pkg).shellLine());
        assertEquals(ShellCommand.Verb.PM_UNINSTALL_UPDATES, ShellCommand.Pm.uninstallUpdates(pkg).verb());
        assertTrue(ShellCommand.Pm.restore(pkg).verb().isPackageManagement());
        assertFalse(ShellCommand.Gmpf.getLevel().verb().isPackageManagement());
    }
}
