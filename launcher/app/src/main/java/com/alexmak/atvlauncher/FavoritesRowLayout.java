package com.alexmak.atvlauncher;

/**
 * Geometry of the favorites row. The first {@code fitCount} tiles (favorites + apps button) are equal 16:9 tiles spread
 * edge to edge over the content width (first flush left, apps button flush right, equal gaps). When they do not fit even
 * at the minimum size the row scrolls with the minimum gap. The row continues after the apps button with
 * {@code total - fitCount} more tiles of the same size and gap that extend past the right edge. All values are pixels.
 */
final class FavoritesRowLayout {
    final int tileWidth;
    final int tileHeight;
    final boolean scrolls;
    private final int fitCount;
    private final int total;
    private final int contentWidth;
    private final double gap;

    private FavoritesRowLayout(int tileWidth, int fitCount, int total, int contentWidth, double gap, boolean scrolls) {
        this.tileWidth = tileWidth;
        this.tileHeight = Math.round(tileWidth * 9f / 16f);
        this.fitCount = fitCount;
        this.total = total;
        this.contentWidth = contentWidth;
        this.gap = gap;
        this.scrolls = scrolls;
    }

    /**
     * @param fitCount tiles that must exactly fill the content width: favorites plus the apps button, at least 1
     * @param total    all tiles of the row including the continuation after the apps button, at least {@code fitCount}
     */
    static FavoritesRowLayout compute(int contentWidth, int fitCount, int total, int minGap, int minTile, int maxTile) {
        if (fitCount < 1) throw new IllegalArgumentException("fitCount < 1");
        if (total < fitCount) throw new IllegalArgumentException("total < fitCount");
        int fit = (contentWidth - (fitCount - 1) * minGap) / fitCount;
        int width = Math.max(minTile, Math.min(maxTile, fit));
        boolean scrolls = fitCount * width + (fitCount - 1) * minGap > contentWidth;
        double gap = scrolls || fitCount == 1 ? minGap
                : (double) (contentWidth - fitCount * width) / (fitCount - 1);
        return new FavoritesRowLayout(width, fitCount, total, contentWidth, gap, scrolls);
    }

    /** Left edge of tile {@code i} relative to the content start. */
    int leftOf(int i) {
        return (int) Math.round(i * (tileWidth + gap));
    }

    /** Space between tile {@code i} and the next one; 0 after the last tile. */
    int gapAfter(int i) {
        return i >= total - 1 ? 0 : leftOf(i + 1) - leftOf(i) - tileWidth;
    }

    /**
     * Scroll position (distance the row is scrolled from its start) that keeps tile {@code index} fully inside the content
     * area, moving as little as possible from {@code current}: the right edge lands on the content's right edge, the left
     * edge on its left edge. While the favorites fit, the favorites and the apps button are always aligned (scroll 0).
     * Never beyond the end of the row.
     */
    int scrollFor(int index, int current) {
        if (!scrolls && index <= fitCount - 1) return 0;
        int left = leftOf(index);
        int right = left + tileWidth;
        int end = Math.max(0, leftOf(total - 1) + tileWidth - contentWidth);
        int target = current;
        if (right - current > contentWidth) target = right - contentWidth;
        else if (left < current) target = left;
        return Math.min(target, end);
    }
}
