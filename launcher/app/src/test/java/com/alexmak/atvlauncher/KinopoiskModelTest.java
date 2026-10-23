package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class KinopoiskModelTest {
    private static String res(String name) throws IOException {
        try (InputStream in = KinopoiskModelTest.class.getClassLoader().getResourceAsStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Test
    public void urlsAreEncoded() {
        assertEquals("https://kinopoiskapiunofficial.tech/api/v2.1/films/search-by-keyword?page=1&keyword=%D0%B2%D0%BE+%D0%B2%D1%81%D0%B5",
                KinopoiskModel.searchUrl("во все"));
        assertEquals("https://kinopoiskapiunofficial.tech/api/v2.2/films/404900", KinopoiskModel.filmUrl(404900));
    }

    @Test
    public void searchFixtureParsesIntoCandidates() throws IOException {
        FetchResult<List<TitleMatch.Candidate>> r = KinopoiskModel.parseSearch(res("kp_search_breaking_bad.json"));
        assertTrue(r.isOk());
        List<TitleMatch.Candidate> list = r.value();
        assertEquals(6, list.size());
        TitleMatch.Candidate series = list.get(0);
        assertEquals(404900, series.id);
        assertEquals("Во все тяжкие", series.nameRu);
        assertEquals("Breaking Bad", series.nameEn);
        assertEquals("TV_SERIES", series.type);
        assertEquals(2008, series.year);
        assertEquals(856947, series.votes);
        assertEquals("FILM", list.get(1).type);
        // the whole chain picks the series for the card "Во все тяжкие" (2008) and the film for 2019
        assertEquals(404900, TitleMatch.pick("Во все тяжкие", 2008, TitleMatch.Kind.SERIES, list).get().id);
        assertEquals(1209193, TitleMatch.pick("El Camino: Во все тяжкие", 2019, TitleMatch.Kind.MOVIE, list).get().id);
    }

    @Test
    public void searchRejectsGarbage() {
        assertFalse(KinopoiskModel.parseSearch("").isOk());
        assertFalse(KinopoiskModel.parseSearch(null).isOk());
        assertFalse(KinopoiskModel.parseSearch("not json").isOk());
        assertFalse(KinopoiskModel.parseSearch("{\"message\":\"x\"}").isOk());
        assertTrue(KinopoiskModel.parseSearch("{\"films\":[]}").value().isEmpty());
    }

    @Test
    public void searchToleratesMissingFields() {
        List<TitleMatch.Candidate> list = KinopoiskModel.parseSearch(
                "{\"films\":[{\"filmId\":7,\"nameRu\":\"А\",\"year\":\"null\",\"rating\":\"null\"},{\"nameRu\":\"no id\"}]}").value();
        assertEquals(1, list.size());
        assertEquals("", list.get(0).nameEn);
        assertEquals("", list.get(0).type);
        assertEquals(0, list.get(0).year);
        assertEquals(0, list.get(0).votes);
    }

    @Test
    public void seriesFilmFixture() throws IOException {
        KinopoiskInfo info = KinopoiskModel.parseFilm(res("kp_film_series.json")).value();
        assertEquals("8.9", info.kp);
        assertEquals("9.5", info.imdb);
        assertTrue(info.description.startsWith("Умирающий учитель химии"));
        assertFalse(info.description.contains(" "));
    }

    @Test
    public void movieFilmFixture() throws IOException {
        KinopoiskInfo info = KinopoiskModel.parseFilm(res("kp_film_movie.json")).value();
        assertEquals("7.2", info.kp);
        assertEquals("7.3", info.imdb);
        assertTrue(info.description.startsWith("Джесси (Аарон Пол) бежит"));
    }

    @Test
    public void missingKpRatingAndShortDescriptionFallBack() throws IOException {
        KinopoiskInfo info = KinopoiskModel.parseFilm(res("kp_film_no_kp_rating.json")).value();
        assertEquals("", info.kp);
        assertEquals("6.3", info.imdb);
        assertTrue("full description cut to sentences", info.description.startsWith("Успешный бизнесмен всю жизнь"));
        assertTrue(info.description.length() <= 400);
    }

    @Test
    public void filmRejectsGarbage() {
        assertFalse(KinopoiskModel.parseFilm("").isOk());
        assertFalse(KinopoiskModel.parseFilm("{").isOk());
        assertFalse(KinopoiskModel.parseFilm("{\"message\":\"Not found\"}").isOk());
        assertEquals("", KinopoiskModel.parseFilm("{\"kinopoiskId\":1,\"ratingKinopoisk\":0,\"ratingImdb\":null}").value().kp);
    }
}
