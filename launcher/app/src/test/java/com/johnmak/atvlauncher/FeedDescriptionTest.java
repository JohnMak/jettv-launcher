package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FeedDescriptionTest {
    @Test
    public void longWinsOverShort() {
        assertEquals("Длинно. Очень.", FeedDescription.of(" Длинно. Очень. ", "Коротко."));
    }

    @Test
    public void shortIsTheFallbackAndIsCutToo() {
        assertEquals("Коротко.", FeedDescription.of(null, " Коротко. "));
        assertEquals("Коротко.", FeedDescription.of(" ", "Коротко."));
        assertEquals("Раз. Два! Три?", FeedDescription.of("", "Раз. Два! Три? Четыре."));
    }

    @Test
    public void cutToThreeSentences() {
        assertEquals("Раз. Два! Три?", FeedDescription.of("Раз. Два! Три? Четыре.", null));
        assertEquals("Раз. Два.", FeedDescription.of("Раз. Два.", ""));
        assertEquals("Одно предложение без точки", FeedDescription.of("Одно предложение без точки", " "));
        assertEquals("", FeedDescription.of(null, null));
    }

    @Test
    public void initialsAndLowercaseAreNotSentenceEnds() {
        assertEquals("Фильм Д. Смита о городе. Второе. Третье.",
                FeedDescription.of("Фильм Д. Смита о городе. Второе. Третье. Четвёртое.", null));
        assertEquals("Он жил в г. москве. Два. Три.", FeedDescription.of("Он жил в г. москве. Два. Три. Четыре.", null));
    }

    @Test
    public void nbspAndNewlinesAreCollapsed() {
        assertEquals("Ричард — профессор. Дальше.", FeedDescription.of("Ричард — профессор.\n\nДальше.", null));
    }
}
