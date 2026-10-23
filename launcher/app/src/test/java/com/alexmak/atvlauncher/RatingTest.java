package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RatingTest {
    private static Rating parse(String raw, int style) {
        return Rating.parse(raw, style).get();
    }

    private static boolean absent(String raw, int style) {
        return !Rating.parse(raw, style).isPresent();
    }

    @Test
    public void starsAreDoubled() {
        assertEquals("8.4", parse("4.2", Rating.STYLE_STARS).text);
        assertEquals("10.0", parse("5", Rating.STYLE_STARS).text);
        assertEquals("0.6", parse(" 0.3 ", Rating.STYLE_STARS).text);
    }

    @Test
    public void percentageIsDividedByTen() {
        assertEquals("8.7", parse("87", Rating.STYLE_PERCENTAGE).text);
        assertEquals("9.9", parse("99.4", Rating.STYLE_PERCENTAGE).text);
        assertEquals("10.0", parse("100", Rating.STYLE_PERCENTAGE).text);
    }

    @Test
    public void thumbsAreShareOfUpVotes() {
        assertEquals("8.3", parse("200,40", Rating.STYLE_THUMBS_UP_DOWN).text);
        assertEquals("10.0", parse("5,0", Rating.STYLE_THUMBS_UP_DOWN).text);
        assertEquals("5.0", parse("10, 10", Rating.STYLE_THUMBS_UP_DOWN).text);
    }

    @Test
    public void overRangeIsClamped() {
        assertEquals("10.0", parse("7", Rating.STYLE_STARS).text);
        assertEquals("10.0", parse("250", Rating.STYLE_PERCENTAGE).text);
    }

    @Test
    public void topIsStrictlyAboveEight() {
        assertFalse(parse("4.0", Rating.STYLE_STARS).top);   // 8.0
        assertTrue(parse("4.05", Rating.STYLE_STARS).top);   // 8.1
        assertFalse(parse("80", Rating.STYLE_PERCENTAGE).top);
        assertTrue(parse("81", Rating.STYLE_PERCENTAGE).top);
        assertFalse(parse("3.5", Rating.STYLE_STARS).top);
        assertTrue(parse("90", Rating.STYLE_PERCENTAGE).top);
    }

    @Test
    public void garbageIsOmitted() {
        assertTrue(absent(null, Rating.STYLE_STARS));
        assertTrue(absent("n/a", Rating.STYLE_STARS));
        assertTrue(absent("", Rating.STYLE_PERCENTAGE));
        assertTrue(absent("0", Rating.STYLE_STARS));
        assertTrue(absent("-3", Rating.STYLE_PERCENTAGE));
        assertTrue(absent("NaN", Rating.STYLE_STARS));
        assertTrue(absent("12000", Rating.STYLE_THUMBS_UP_DOWN));
        assertTrue(absent("a,b", Rating.STYLE_THUMBS_UP_DOWN));
        assertTrue(absent("0,0", Rating.STYLE_THUMBS_UP_DOWN));
        assertTrue(absent("-1,5", Rating.STYLE_THUMBS_UP_DOWN));
        assertTrue(absent("4.5", 7));
    }
}
