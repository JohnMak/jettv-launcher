package com.johnmak.atvlauncher;

/**
 * The overflow-safe decoded-byte budget and the power-of-two subsample that enforces it (plan B4) — a NEW helper,
 * deliberately SEPARATE from {@link PosterSampling#sampleSize} (which is shared by {@link PosterCache} for poster art
 * and must not change behaviour). Pure; unit-tested offline.
 *
 * <p>Unlike {@link PosterSampling#sampleSize} (largest power-of-two keeping the decoded image at or above the target —
 * a FLOOR), this picks the SMALLEST power-of-two {@code inSampleSize} whose decoded estimate fits within a hard
 * decoded-byte budget — i.e. the LARGEST image that still fits the budget. For a 4K panel a true-4K source decodes
 * full-size; for a 1080p panel the same source is subsampled to ~panel size. The estimate uses CEILING division and
 * {@code long} math so it is overflow-safe for any sane source dimension.
 */
final class DecodeBudget {
    /** A source edge beyond this is absurd/corrupt and rejected before any work (admits any positive true-4K+ source). */
    static final int MAX_SOURCE_EDGE = 32768;
    /** The largest subsample we will attempt; beyond this even a huge source cannot fit any sane budget → reject. */
    static final int MAX_SAMPLE_SIZE = 32;

    private DecodeBudget() {}

    /** True when the bounds-decode dimensions are usable: positive and within {@link #MAX_SOURCE_EDGE} per edge.
     * Replaces {@code ArtScreensaverView.MAX_SOURCE_EDGE=4000} — a true-4K+ source (5120×2880, 7680×4320) is ADMITTED;
     * only a corrupt ({@code ≤0}) or absurd ({@code > 32768}) edge is rejected. */
    static boolean acceptable(int srcW, int srcH) {
        return srcW > 0 && srcH > 0 && srcW <= MAX_SOURCE_EDGE && srcH <= MAX_SOURCE_EDGE;
    }

    /** The decoded-byte budget for a panel of {@code targetW × targetH}: {@code ≈1.5 × panel bytes} (ARGB_8888, i.e.
     * {@code 1.5 × W × H × 4 = 6 × W × H}). ~50 MB at 4K, ~12.4 MB at 1080p. {@code long} — overflow-safe. */
    static long budgetBytes(int targetW, int targetH) {
        return 6L * targetW * targetH;
    }

    /** Overflow-safe ceiling division for positive operands. */
    static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /** The decoded size in bytes of {@code src} decoded at {@code sample}, using CEILING division (BitmapFactory rounds
     * the subsampled dimension UP), in {@code long} math — overflow-safe. {@code bytesPerPixel} is the decoded config's
     * actual stride: 4 for ARGB_8888, but 8 for RGBA_F16 — which Android may promote a 16-bit PNG to DESPITE an
     * ARGB_8888 preference, so the budget MUST account for it (the caller reads it from {@code Options.outConfig}). */
    static long estimateBytes(int srcW, int srcH, int sample, int bytesPerPixel) {
        return ceilDiv(srcW, sample) * ceilDiv(srcH, sample) * bytesPerPixel;
    }

    /**
     * The SMALLEST power-of-two {@code inSampleSize} ({@code 1 ≤ s ≤ }{@link #MAX_SAMPLE_SIZE}) whose decoded estimate
     * (at {@code bytesPerPixel}) is {@code ≤ budgetBytes}; {@code 0} when even the largest sample cannot fit (the
     * source is rejected rather than allocated over-budget). Enforced on the cheap bounds pass, BEFORE any pixel decode.
     */
    static int sampleSizeForBudget(int srcW, int srcH, long budgetBytes, int bytesPerPixel) {
        for (int s = 1; s <= MAX_SAMPLE_SIZE; s <<= 1) {
            if (estimateBytes(srcW, srcH, s, bytesPerPixel) <= budgetBytes) return s;
        }
        return 0;
    }
}
