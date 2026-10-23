package com.alexmak.atvlauncher;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class GeocodingModelTest {
    private static String fixture(String name) throws Exception {
        try (InputStream in = GeocodingModelTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void urlEncodesTheQuery() {
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name="
                        + "%D0%9D%D0%BE%D0%B2%D0%BE%D1%81%D0%B8%D0%B1%D0%B8%D1%80%D1%81%D0%BA"
                        + "&count=8&language=ru&format=json",
                GeocodingModel.url("Новосибирск"));
        assertTrue(GeocodingModel.url("  Санкт Петербург ").contains("name=%D0%A1%D0%B0%D0%BD%D0%BA%D1%82+%D0%9F"));
        assertTrue(GeocodingModel.url("  Санкт Петербург ").contains("%B3&count=8"));
    }

    @Test public void parsesResults() throws Exception {
        FetchResult<List<GeocodingModel.Place>> r = GeocodingModel.parse(fixture("geocoding_ok.json"));
        assertTrue(r.isOk());
        assertEquals(2, r.value().size());
        GeocodingModel.Place p = r.value().get(0);
        assertEquals("Новосибирск, Новосибирская Область, Россия", p.label());
        GeoLocation g = p.toLocation();
        assertEquals("Новосибирск", g.cityRu);
        assertEquals(55.02259, g.lat, 1e-9);
        assertEquals(82.93175, g.lon, 1e-9);
        assertEquals("RU", g.country);
        assertEquals("Asia/Novosibirsk", g.timezoneId);
    }

    @Test public void noMatchesIsAnEmptyList() throws Exception {
        FetchResult<List<GeocodingModel.Place>> r = GeocodingModel.parse(fixture("geocoding_empty.json"));
        assertTrue(r.isOk());
        assertTrue(r.value().isEmpty());
    }

    @Test public void rejectsMalformedResponses() throws Exception {
        assertFalse(GeocodingModel.parse(fixture("geocoding_no_timezone.json")).isOk());
        assertFalse(GeocodingModel.parse("{").isOk());
        assertFalse(GeocodingModel.parse("").isOk());
        assertFalse(GeocodingModel.parse(null).isOk());
    }
}
