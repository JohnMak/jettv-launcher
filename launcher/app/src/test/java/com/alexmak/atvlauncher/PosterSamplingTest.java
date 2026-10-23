package com.alexmak.atvlauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PosterSamplingTest {
    @Test
    public void sampleKeepsDecodeAtLeastRequested() {
        assertEquals(1, PosterSampling.sampleSize(480, 270, 480, 270));
        assertEquals(2, PosterSampling.sampleSize(1000, 600, 480, 270));
        assertEquals(4, PosterSampling.sampleSize(2000, 1200, 480, 270));
        assertEquals(1, PosterSampling.sampleSize(100, 100, 480, 270));
    }

    @Test
    public void coverSizeIsTheExactCardSizeAlongTheTightSideAndKeepsAspect() {
        assertArrayEquals(new int[]{480, 270}, PosterSampling.coverSize(1000, 562, 480, 270));
        assertArrayEquals(new int[]{480, 720}, PosterSampling.coverSize(1000, 1500, 480, 270));
        assertArrayEquals(new int[]{600, 270}, PosterSampling.coverSize(1000, 450, 480, 270));
    }

    @Test
    public void coverSizeNeverUpscales() {
        assertArrayEquals(new int[]{300, 200}, PosterSampling.coverSize(300, 200, 480, 270));
        assertArrayEquals(new int[]{480, 270}, PosterSampling.coverSize(480, 270, 480, 270));
    }
}
