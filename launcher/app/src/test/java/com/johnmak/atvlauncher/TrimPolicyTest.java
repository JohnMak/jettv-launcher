package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentCallbacks2;

import org.junit.Test;

public class TrimPolicyTest {
    @Test
    public void cachesAreDroppedOnceTheUiIsHiddenAndWorse() {
        assertTrue(TrimPolicy.evictsCaches(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN));
        assertTrue(TrimPolicy.evictsCaches(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND));
        assertTrue(TrimPolicy.evictsCaches(ComponentCallbacks2.TRIM_MEMORY_COMPLETE));
        assertFalse(TrimPolicy.evictsCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL));
        assertFalse(TrimPolicy.evictsCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE));
    }
}
