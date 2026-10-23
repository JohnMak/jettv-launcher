package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BrightnessSupportTest {
    @Test
    public void detectsByGmpfUnitPresence() {
        List<String> asked = new ArrayList<>();
        assertTrue(BrightnessSupport.detect(p -> { asked.add(p); return true; }));
        assertFalse(BrightnessSupport.detect(p -> { asked.add(p); return false; }));
        assertEquals("/system/bin/gmpfUnit", asked.get(0));
        assertEquals(2, asked.size());
    }

    @Test
    public void absentFeatureHasNoTopItems() {
        assertTrue(BrightnessFeature.ABSENT.topItems().isEmpty());
        BrightnessFeature.ABSENT.show();
        BrightnessFeature.ABSENT.step(1);
        BrightnessFeature.ABSENT.release();
    }
}
