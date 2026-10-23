package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class GmpfCommandTest {
    @Test
    public void onlyTheWhitelistedVerbsExist() {
        assertEquals(Arrays.asList("GET_DLP_LUMENS_LEVEL", "GET_BRIGHTNESS", "GET_CONTRAST",
                        "SET_DLP_LUMENS_LEVEL", "SET_BRIGHTNESS", "SET_CONTRAST"),
                Arrays.stream(GmpfCommand.Verb.values()).map(Enum::name).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    public void gettersBuildExactShellLines() {
        assertEquals("gmpfUnit getDlpLumensLevel", GmpfCommand.getLevel().shellLine());
        assertEquals("gmpfUnit getBrightness 0", GmpfCommand.getBrightness().shellLine());
        assertEquals("gmpfUnit getContrast 0", GmpfCommand.getContrast().shellLine());
        assertEquals(GmpfCommand.Verb.GET_CONTRAST, GmpfCommand.getContrast().verb());
    }

    @Test
    public void settersBuildExactShellLines() {
        for (int v = LampLevel.MIN_LEVEL; v <= LampLevel.MAX_LEVEL; v++) {
            assertEquals("gmpfUnit setDlpLumensLevel " + v, GmpfCommand.setLevel(LampLevel.of(v).value()).shellLine());
        }
        assertEquals("gmpfUnit setBrightness 0 0", GmpfCommand.setBrightness(0).value().shellLine());
        assertEquals("gmpfUnit setBrightness 0 57", GmpfCommand.setBrightness(57).value().shellLine());
        assertEquals("gmpfUnit setContrast 0 100", GmpfCommand.setContrast(100).value().shellLine());
        assertEquals(GmpfCommand.Verb.SET_BRIGHTNESS, GmpfCommand.setBrightness(1).value().verb());
    }

    @Test
    public void outOfRangePictureValuesAreRejectedAsValues() {
        for (int bad : new int[] {-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertFalse("brightness " + bad, GmpfCommand.setBrightness(bad).isOk());
            assertFalse("contrast " + bad, GmpfCommand.setContrast(bad).isOk());
            assertEquals(LampError.COMMAND_FAILED, LampError.of(GmpfCommand.setBrightness(bad)));
        }
        assertTrue(GmpfCommand.setContrast(0).isOk());
    }

    @Test
    public void lampLevelRangeIsEnforcedByTheLevelType() {
        assertFalse(LampLevel.of(0).isOk());
        assertFalse(LampLevel.of(11).isOk());
    }
}
