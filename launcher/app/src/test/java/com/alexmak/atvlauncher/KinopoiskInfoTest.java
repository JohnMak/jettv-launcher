package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KinopoiskInfoTest {
    @Test
    public void shortDescriptionWins() {
        assertEquals("Коротко.", KinopoiskInfo.describe(" Коротко. ", "Длинно. Очень. Длинно. Да."));
    }

    @Test
    public void fullDescriptionIsCutToThreeSentences() {
        assertEquals("Раз. Два! Три?", KinopoiskInfo.describe(null, "Раз. Два! Три? Четыре."));
        assertEquals("Раз. Два.", KinopoiskInfo.describe("", "Раз. Два."));
        assertEquals("Одно предложение без точки", KinopoiskInfo.describe(" ", "Одно предложение без точки"));
        assertEquals("", KinopoiskInfo.describe(null, null));
    }

    @Test
    public void initialsAndLowercaseAreNotSentenceEnds() {
        assertEquals("Фильм Д. Смита о городе. Второе. Третье.",
                KinopoiskInfo.describe(null, "Фильм Д. Смита о городе. Второе. Третье. Четвёртое."));
        assertEquals("Он жил в г. москве. Два. Три.", KinopoiskInfo.describe(null, "Он жил в г. москве. Два. Три. Четыре."));
    }

    @Test
    public void nbspAndNewlinesAreCollapsed() {
        assertEquals("Ричард — профессор. Дальше.", KinopoiskInfo.describe(null, "Ричард — профессор.\n\nДальше."));
    }

    @Test
    public void noneIsEmpty() {
        assertTrue(KinopoiskInfo.NONE.isEmpty());
        assertFalse(new KinopoiskInfo("", "7.0", "").isEmpty());
    }
}
