package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KinopoiskInfoTest {
    @Test
    public void noneIsEmpty() {
        assertTrue(KinopoiskInfo.NONE.isEmpty());
        assertFalse(new KinopoiskInfo("", "7.0").isEmpty());
    }
}
