package com.johnmak.atvlauncher;

/** Pure sizing of a poster decode: the power-of-two subsample and the exact size it is then scaled to. */
final class PosterSampling {
    private PosterSampling() {
    }

    /** Largest power-of-two {@code inSampleSize} that keeps the decoded image at least reqW x reqH. */
    static int sampleSize(int srcW, int srcH, int reqW, int reqH) {
        int sample = 1;
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) sample *= 2;
        return sample;
    }

    /**
     * Size to scale a decoded w x h image to: the smallest size with the same aspect that covers reqW x reqH.
     * An image that is not larger than that is kept as it is (never upscaled).
     */
    static int[] coverSize(int w, int h, int reqW, int reqH) {
        if (w <= reqW || h <= reqH) return new int[]{w, h};
        double scale = Math.max((double) reqW / w, (double) reqH / h);
        return new int[]{Math.max(reqW, (int) Math.round(w * scale)), Math.max(reqH, (int) Math.round(h * scale))};
    }
}
