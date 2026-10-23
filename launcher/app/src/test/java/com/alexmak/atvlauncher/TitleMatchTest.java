package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

public class TitleMatchTest {
    private static TitleMatch.Candidate c(long id, String ru, String en, String type, int year, int votes) {
        return new TitleMatch.Candidate(id, ru, en, type, year, votes);
    }

    @Test
    public void normalizeStripsNoise() {
        assertEquals("во все тяжкие", TitleMatch.normalize("Во все тяжкие"));
        assertEquals("во все тяжкие", TitleMatch.normalize("Во все тяжкие (сериал)"));
        assertEquals("во все тяжкие", TitleMatch.normalize("Во все тяжкие, 2 сезон"));
        assertEquals("во все тяжкие", TitleMatch.normalize("Во все тяжкие: Сезон 5"));
        assertEquals("во все тяжкие", TitleMatch.normalize("Во все тяжкие - 3-й сезон"));
        assertEquals("breaking bad", TitleMatch.normalize("Breaking Bad (2008)"));
        assertEquals("breaking bad", TitleMatch.normalize("Breaking Bad: Season 4"));
        assertEquals("все", TitleMatch.normalize("Всё!"));
        assertEquals("1984", TitleMatch.normalize("1984"));
        assertEquals("", TitleMatch.normalize("  ... "));
        assertEquals("", TitleMatch.normalize(null));
    }

    @Test
    public void cleanKeepsYoForTheSearchKeyword() {
        assertEquals("всё", TitleMatch.clean("Всё (2019)"));
    }

    @Test
    public void seasonWordInsideATitleSurvives() {
        assertEquals("сезон охоты", TitleMatch.normalize("Сезон охоты"));
        assertEquals("the season of rain", TitleMatch.normalize("The Season of Rain"));
    }

    @Test
    public void kindFollowsSeasonEpisodeThenType() {
        assertEquals(TitleMatch.Kind.EPISODE, TitleMatch.kindOf("2", "", FeedItem.NO_TYPE));
        assertEquals(TitleMatch.Kind.EPISODE, TitleMatch.kindOf("", "5", 0));
        assertEquals(TitleMatch.Kind.EPISODE, TitleMatch.kindOf("", "", 3));
        assertEquals(TitleMatch.Kind.EPISODE, TitleMatch.kindOf("", "", 2));
        assertEquals(TitleMatch.Kind.SERIES, TitleMatch.kindOf("", "", 1));
        assertEquals(TitleMatch.Kind.MOVIE, TitleMatch.kindOf("", "", 0));
        assertEquals(TitleMatch.Kind.UNKNOWN, TitleMatch.kindOf("", "", FeedItem.NO_TYPE));
        assertEquals(TitleMatch.Kind.UNKNOWN, TitleMatch.kindOf("", "", 4));
    }

    @Test
    public void keyIgnoresNoiseButNotYear() {
        assertEquals(TitleMatch.key("Во все тяжкие", 2008, TitleMatch.Kind.SERIES),
                TitleMatch.key("ВО ВСЕ ТЯЖКИЕ (сериал)", 2008, TitleMatch.Kind.SERIES));
        assertFalse(TitleMatch.key("X", 2011, TitleMatch.Kind.EPISODE).equals(TitleMatch.key("X", 2020, TitleMatch.Kind.EPISODE)));
        assertEquals("unknown year: no lookup, no key", "", TitleMatch.key("X", 0, TitleMatch.Kind.MOVIE));
        assertFalse(TitleMatch.key("X", 2011, TitleMatch.Kind.MOVIE).equals(TitleMatch.key("X", 2012, TitleMatch.Kind.MOVIE)));
        assertFalse(TitleMatch.key("X", 2000, TitleMatch.Kind.MOVIE).equals(TitleMatch.key("X", 2000, TitleMatch.Kind.SERIES)));
        assertEquals("", TitleMatch.key("(2019)", 2019, TitleMatch.Kind.MOVIE));
    }

    @Test
    public void picksExactNameOfTheRightTypeWithMostVotes() {
        List<TitleMatch.Candidate> list = Arrays.asList(
                c(1, "Во все тяжкие", "Kaalakaandi", "FILM", 2018, 297),
                c(2, "Во все тяжкие", "Breaking Bad", "TV_SERIES", 2008, 856947),
                c(3, "El Camino: Во все тяжкие", "El Camino", "FILM", 2019, 111629),
                c(4, "Во все тяжкие", "La calda vita", "FILM", 1964, 140));
        assertEquals(2, TitleMatch.pick("Во все тяжкие", 2008, TitleMatch.Kind.SERIES, list).get().id);
        assertEquals(2, TitleMatch.pick("Breaking Bad", 2008, TitleMatch.Kind.UNKNOWN, list).get().id);
        assertEquals(1, TitleMatch.pick("Во все тяжкие", 2018, TitleMatch.Kind.MOVIE, list).get().id);
        assertEquals(4, TitleMatch.pick("Во все тяжкие", 1964, TitleMatch.Kind.MOVIE, list).get().id);
    }

    @Test
    public void onlyTheExactYearMatches() {
        List<TitleMatch.Candidate> list = Collections.singletonList(c(2, "Во все тяжкие", "Breaking Bad", "TV_SERIES", 2008, 10));
        assertFalse(TitleMatch.pick("Во все тяжкие", 2012, TitleMatch.Kind.SERIES, list).isPresent());
        assertFalse("+1 year", TitleMatch.pick("Во все тяжкие", 2009, TitleMatch.Kind.SERIES, list).isPresent());
        assertFalse("-1 year", TitleMatch.pick("Во все тяжкие", 2007, TitleMatch.Kind.SERIES, list).isPresent());
        assertFalse("episode year is checked too", TitleMatch.pick("Во все тяжкие", 2013, TitleMatch.Kind.EPISODE, list).isPresent());
        assertTrue(TitleMatch.pick("Во все тяжкие", 2008, TitleMatch.Kind.SERIES, list).isPresent());
        assertFalse("unknown app year", TitleMatch.pick("Во все тяжкие", 0, TitleMatch.Kind.SERIES, list).isPresent());
        assertFalse("unknown candidate year",
                TitleMatch.pick("Х", 2012, TitleMatch.Kind.MOVIE, Collections.singletonList(c(5, "Х", "", "FILM", 0, 1))).isPresent());
    }

    @Test
    public void noExactNameMeansNoMatch() {
        List<TitleMatch.Candidate> list = Collections.singletonList(c(3, "El Camino: Во все тяжкие", "El Camino", "FILM", 2019, 5));
        assertEquals(Optional.empty(), TitleMatch.pick("Во все тяжкие", 2019, TitleMatch.Kind.MOVIE, list));
        assertEquals(Optional.empty(), TitleMatch.pick("", 2000, TitleMatch.Kind.UNKNOWN, list));
        assertEquals(Optional.empty(), TitleMatch.pick("X", 2000, TitleMatch.Kind.UNKNOWN, Collections.emptyList()));
    }

    @Test
    public void typeFilterSeparatesSeriesFromFilms() {
        List<TitleMatch.Candidate> list = Arrays.asList(
                c(1, "Мир", "Peace", "FILM", 2000, 100),
                c(2, "Мир", "Peace", "MINI_SERIES", 2000, 50),
                c(3, "Мир", "Peace", "TV_SHOW", 2000, 10));
        assertEquals(1, TitleMatch.pick("Мир", 2000, TitleMatch.Kind.MOVIE, list).get().id);
        assertEquals(2, TitleMatch.pick("Мир", 2000, TitleMatch.Kind.EPISODE, list).get().id);
        assertEquals(1, TitleMatch.pick("Мир", 2000, TitleMatch.Kind.UNKNOWN, list).get().id);
    }
}
