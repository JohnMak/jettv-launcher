package com.alexmak.atvlauncher;

import java.util.Locale;
import java.util.Optional;

/**
 * A review rating on one 10-point scale, shown with one decimal ("8.4"). Parsed from the TvContract
 * REVIEW_RATING string according to REVIEW_RATING_STYLE:
 * stars "0.0".."5.0" (x2), percentage "0".."100" (/10), thumbs "up,down" counts (share of ups x10).
 */
final class Rating {
    // TvContract.REVIEW_RATING_STYLE_* values
    static final int STYLE_STARS = 0;
    static final int STYLE_THUMBS_UP_DOWN = 1;
    static final int STYLE_PERCENTAGE = 2;

    /** Ratings above this value (8.1 and more) are "top". */
    private static final int TOP_ABOVE_TENTHS = 80;
    private static final int MAX_TENTHS = 100;

    /** Display form with a dot and one decimal, e.g. "8.4". */
    final String text;
    final boolean top;

    private Rating(int tenths) {
        this.text = String.format(Locale.ROOT, "%d.%d", tenths / 10, tenths % 10);
        this.top = tenths > TOP_ABOVE_TENTHS;
    }

    /** Empty when the rating is absent, in an unknown style, unparsable or not positive. */
    static Optional<Rating> parse(String raw, int style) {
        if (raw == null) return Optional.empty();
        double ten;
        try {
            switch (style) {
                case STYLE_STARS:
                    ten = Double.parseDouble(raw.trim()) * 2;
                    break;
                case STYLE_PERCENTAGE:
                    ten = Double.parseDouble(raw.trim()) / 10;
                    break;
                case STYLE_THUMBS_UP_DOWN:
                    ten = thumbsShare(raw);
                    break;
                default:
                    return Optional.empty();
            }
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (Double.isNaN(ten) || ten <= 0) return Optional.empty();
        return Optional.of(new Rating((int) Math.min(MAX_TENTHS, Math.round(ten * 10))));
    }

    /** "up,down" counts to a 0..10 value; NaN when nobody voted. */
    private static double thumbsShare(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 2) throw new NumberFormatException(raw);
        double up = Double.parseDouble(parts[0].trim());
        double down = Double.parseDouble(parts[1].trim());
        if (up < 0 || down < 0) throw new NumberFormatException(raw);
        return up + down == 0 ? Double.NaN : up / (up + down) * 10;
    }
}
