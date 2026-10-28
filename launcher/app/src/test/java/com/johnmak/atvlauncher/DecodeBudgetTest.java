package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DecodeBudgetTest {
    @Test public void acceptableAdmitsTrue4KplusAndRejectsCorruptOrAbsurd() {
        assertTrue("1080p", DecodeBudget.acceptable(1920, 1080));
        assertTrue("4K", DecodeBudget.acceptable(3840, 2160));
        assertTrue("5K", DecodeBudget.acceptable(5120, 2880));
        assertTrue("8K — the old 4000px cap would have wrongly rejected this", DecodeBudget.acceptable(7680, 4320));
        assertTrue("sanity ceiling edge", DecodeBudget.acceptable(DecodeBudget.MAX_SOURCE_EDGE, 10));
        assertFalse("zero width = corrupt", DecodeBudget.acceptable(0, 100));
        assertFalse("zero height = corrupt", DecodeBudget.acceptable(100, 0));
        assertFalse("negative = corrupt", DecodeBudget.acceptable(-1, 100));
        assertFalse("beyond the sanity ceiling", DecodeBudget.acceptable(DecodeBudget.MAX_SOURCE_EDGE + 1, 10));
    }

    @Test public void budgetIsRoughlyOnePointFivePanelBytes() {
        assertEquals("1.5 × 4K × 4 bytes = 6 × W × H", 6L * 3840 * 2160, DecodeBudget.budgetBytes(3840, 2160));
        assertEquals(6L * 1920 * 1080, DecodeBudget.budgetBytes(1920, 1080));
    }

    @Test public void ceilDivRoundsUp() {
        assertEquals(1, DecodeBudget.ceilDiv(1, 2));
        assertEquals(2, DecodeBudget.ceilDiv(3, 2));
        assertEquals(960, DecodeBudget.ceilDiv(1919, 2)); // BitmapFactory rounds the subsampled edge up
        assertEquals(1, DecodeBudget.ceilDiv(1, 1));
    }

    @Test public void estimateUsesCeilingAndIsOverflowSafeAtHugeDimensions() {
        // 4K at sample 1 = 3840*2160*4 (ARGB_8888)
        assertEquals(3840L * 2160 * 4, DecodeBudget.estimateBytes(3840, 2160, 1, 4));
        // sample 2 halves each edge (ceiling)
        assertEquals(1920L * 1080 * 4, DecodeBudget.estimateBytes(3840, 2160, 2, 4));
        // RGBA_F16 is 8 bytes/px — the estimate must double
        assertEquals(3840L * 2160 * 8, DecodeBudget.estimateBytes(3840, 2160, 1, 8));
        // the extreme corner must not overflow a long (32768² × 8 ≈ 8.6e9 — would overflow an int)
        long huge = DecodeBudget.estimateBytes(32768, 32768, 1, 8);
        assertEquals(32768L * 32768 * 8, huge);
        assertTrue("overflow-safe (positive)", huge > 0);
    }

    @Test public void sampleSizeForBudgetPicksLargestImageWithinBudget() {
        // 4K panel budget: a true-4K ARGB_8888 source fits at sample 1 (full resolution — admit ≥4K)
        long panel4k = DecodeBudget.budgetBytes(3840, 2160);
        assertEquals(1, DecodeBudget.sampleSizeForBudget(3840, 2160, panel4k, 4));
        // 1080p panel budget: the same 4K source must subsample to sample 2 (~panel size)
        long panel1080 = DecodeBudget.budgetBytes(1920, 1080);
        assertEquals(2, DecodeBudget.sampleSizeForBudget(3840, 2160, panel1080, 4));
        // a small source is never upsampled — sample stays 1
        assertEquals(1, DecodeBudget.sampleSizeForBudget(800, 600, panel1080, 4));
    }

    @Test public void f16PromotionIsAccountedForAndDownsampledFurther() {
        // A 16-bit PNG Android promotes to RGBA_F16 (8 B/px): a true-4K F16 source at sample 1 would be ~66.4 MB,
        // OVER the ~49.8 MB 4K budget — so the budget must force sample 2 (≈16.6 MB), never sample 1.
        long panel4k = DecodeBudget.budgetBytes(3840, 2160);
        assertEquals("F16 4K must NOT decode at full size against the ARGB_8888 budget", 1,
                DecodeBudget.sampleSizeForBudget(3840, 2160, panel4k, 4)); // the 4-byte case fits at 1
        assertEquals("the same source at 8 B/px is forced to sample 2", 2,
                DecodeBudget.sampleSizeForBudget(3840, 2160, panel4k, 8));
        assertTrue("and that sample genuinely fits the budget",
                DecodeBudget.estimateBytes(3840, 2160, 2, 8) <= panel4k);
    }

    @Test public void sampleSizeRejectsWhenEvenTheLargestSampleCannotFit() {
        // an absurd source against a tiny budget: no power-of-two up to MAX fits → 0 (reject, do not allocate)
        assertEquals(0, DecodeBudget.sampleSizeForBudget(32768, 32768, 1024, 4));
        // the smallest sample that fits is returned, never a larger-than-needed one
        int s = DecodeBudget.sampleSizeForBudget(7680, 4320, DecodeBudget.budgetBytes(1920, 1080), 4);
        assertTrue("subsamples an 8K source down to fit a 1080p budget", s >= 2);
        assertTrue("power of two within the cap", s <= DecodeBudget.MAX_SAMPLE_SIZE && (s & (s - 1)) == 0);
        assertTrue("and the chosen sample actually fits the budget",
                DecodeBudget.estimateBytes(7680, 4320, s, 4) <= DecodeBudget.budgetBytes(1920, 1080));
    }
}
