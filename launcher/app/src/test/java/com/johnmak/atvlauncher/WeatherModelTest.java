package com.johnmak.atvlauncher;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;
import org.junit.Test;

public class WeatherModelTest {
    private static String fixture(String name) throws Exception {
        try (InputStream in = WeatherModelTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final TimeZone NSK = TimeZone.getTimeZone("Asia/Novosibirsk");

    @Test public void parsesMetNorway() throws Exception {
        FetchResult<Weather> r = WeatherModel.parse(fixture("met_compact_ok.json"), 1000L, NSK);
        assertTrue(r.isOk());
        Weather w = r.value();
        assertEquals(1.8, w.tempC, 1e-9);
        // first entry: next_1_hours = "cloudy" (no suffix), 19:00Z = 02:00 local -> night
        assertEquals(WeatherIcon.CLOUD, w.icon);
        assertFalse(w.isDay);
        // next 24 h: local 07-19 max, local 19-07 min
        assertEquals(3.8, w.maxC, 1e-9);
        assertEquals(-0.8, w.minC, 1e-9);
        assertEquals(1000L, w.fetchedAtMillis);
        assertEquals("+2°", w.tempText());
        assertEquals("+4° / −1°", w.rangeText());
    }

    @Test public void usesNextSixHoursWhenOneHourBucketAbsent() throws Exception {
        FetchResult<Weather> r = WeatherModel.parse(fixture("met_compact_six_hour.json"), 0L, NSK);
        assertTrue(r.isOk());
        assertEquals(WeatherIcon.CLOUD_MOON, r.value().icon);
        assertFalse(r.value().isDay);
    }

    @Test public void parseErrors() throws Exception {
        assertFalse(WeatherModel.parse(fixture("met_compact_missing.json"), 0, NSK).isOk());
        assertFalse(WeatherModel.parse(fixture("met_compact_empty.json"), 0, NSK).isOk());
        assertFalse(WeatherModel.parse(fixture("met_compact_no_symbol.json"), 0, NSK).isOk());
        assertFalse(WeatherModel.parse("not json", 0, NSK).isOk());
        assertFalse(WeatherModel.parse("", 0, NSK).isOk());
        assertFalse(WeatherModel.parse(null, 0, NSK).isOk());
    }

    @Test public void urlAndUserAgent() {
        assertEquals("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=55.7558&lon=-37.6000",
                WeatherModel.url(55.7558, -37.6));
        assertTrue(HttpText.USER_AGENT.startsWith("atvlauncher/"));
        assertTrue(HttpText.USER_AGENT.contains("@"));
    }

    @Test public void dayFlag() {
        assertTrue(WeatherModel.isDay("clearsky_day", 3));
        assertFalse(WeatherModel.isDay("clearsky_night", 12));
        assertTrue(WeatherModel.isDay("fair_polartwilight", 3));
        // no suffix: local hour in [7,19)
        assertFalse(WeatherModel.isDay("cloudy", 6));
        assertTrue(WeatherModel.isDay("cloudy", 7));
        assertTrue(WeatherModel.isDay("cloudy", 18));
        assertFalse(WeatherModel.isDay("cloudy", 19));
        assertFalse(WeatherModel.isDay("rain", 0));
    }

    @Test public void iconMapping() {
        assertEquals(WeatherIcon.SUN, WeatherIcon.fromMet("clearsky_day", true));
        assertEquals(WeatherIcon.MOON, WeatherIcon.fromMet("clearsky_night", false));
        assertEquals(WeatherIcon.SUN, WeatherIcon.fromMet("fair_polartwilight", true));
        assertEquals(WeatherIcon.MOON, WeatherIcon.fromMet("fair_night", false));
        assertEquals(WeatherIcon.CLOUD_SUN, WeatherIcon.fromMet("partlycloudy_day", true));
        assertEquals(WeatherIcon.CLOUD_MOON, WeatherIcon.fromMet("partlycloudy_night", false));
        for (boolean d : new boolean[] {true, false}) {
            assertEquals(WeatherIcon.CLOUD, WeatherIcon.fromMet("cloudy", d));
            assertEquals(WeatherIcon.CLOUD_FOG, WeatherIcon.fromMet("fog", d));
            assertEquals(WeatherIcon.CLOUD_DRIZZLE, WeatherIcon.fromMet("lightrain", d));
            assertEquals(WeatherIcon.CLOUD_RAIN, WeatherIcon.fromMet("rain", d));
            assertEquals(WeatherIcon.CLOUD_RAIN_WIND, WeatherIcon.fromMet("heavyrain", d));
            for (String s : new String[] {"sleet", "lightsleet", "heavysleet",
                    "sleetshowers_day", "lightsleetshowers_night", "heavysleetshowers_polartwilight"})
                assertEquals(s, WeatherIcon.CLOUD_HAIL, WeatherIcon.fromMet(s, d));
            for (String s : new String[] {"snow", "lightsnow", "heavysnow",
                    "snowshowers_day", "lightsnowshowers_night", "heavysnowshowers_day"})
                assertEquals(s, WeatherIcon.CLOUD_SNOW, WeatherIcon.fromMet(s, d));
            for (String s : new String[] {"rainandthunder", "lightrainandthunder", "heavyrainandthunder",
                    "sleetandthunder", "snowandthunder", "lightsleetshowersandthunder_day",
                    "rainshowersandthunder_night", "heavysnowshowersandthunder_day",
                    "lightrainshowersandthunder_polartwilight"})
                assertEquals(s, WeatherIcon.CLOUD_LIGHTNING, WeatherIcon.fromMet(s, d));
            for (String s : new String[] {"foo", "", "clearskyy"})
                assertEquals(s, WeatherIcon.CLOUD_OFF, WeatherIcon.fromMet(s, d));
        }
        for (String s : new String[] {"rainshowers_day", "lightrainshowers_day", "heavyrainshowers_day"})
            assertEquals(s, WeatherIcon.CLOUD_SUN_RAIN, WeatherIcon.fromMet(s, true));
        for (String s : new String[] {"rainshowers_night", "lightrainshowers_night", "heavyrainshowers_night"})
            assertEquals(s, WeatherIcon.CLOUD_MOON_RAIN, WeatherIcon.fromMet(s, false));
        assertEquals(WeatherIcon.CLOUD_SUN_RAIN, WeatherIcon.fromMet("rainshowers_polartwilight", true));
    }

    @Test public void metPhenomenon() {
        String[][] cases = {
            {"clearsky_day", ""}, {"partlycloudy_night", ""}, {"cloudy", ""},
            {"lightrain", "дождь"}, {"rain", "дождь"}, {"lightrain_day", "дождь"}, {"heavyrain", "ливень"},
            {"rainshowers_day", "ливень"}, {"lightrainshowers_night", "ливень"}, {"heavyrainshowers_day", "ливень"},
            {"sleet", "мокрый снег"}, {"lightsleetshowers_day", "мокрый снег"}, {"heavysleet", "мокрый снег"},
            {"snow", "снег"}, {"lightsnowshowers_night", "снег"}, {"heavysnow", "снег"},
            {"rainandthunder", "гроза"}, {"heavyrainshowersandthunder_day", "гроза"}, {"snowandthunder", "гроза"},
            {"fog", "туман"},
        };
        for (String[] c : cases) assertEquals(c[0], c[1], WeatherModel.phenomenon(c[0]));
    }

    @Test public void formatting() {
        assertEquals("+5°", Weather.formatTemp(5.0));
        assertEquals("+5°", Weather.formatTemp(4.6));
        assertEquals("−3°", Weather.formatTemp(-3.2));
        assertEquals("−3°", Weather.formatTemp(-2.6));
        assertEquals("0°", Weather.formatTemp(-0.4));
        assertEquals("0°", Weather.formatTemp(0.4));
        assertEquals("0°", Weather.formatTemp(0.0));
        assertEquals("+1°", Weather.formatTemp(0.6));
        assertEquals("−1°", Weather.formatTemp(-0.6));
        assertEquals("−25°", Weather.formatTemp(-25.0));
    }

    @Test public void geoParser() throws Exception {
        FetchResult<GeoLocation> ok = GeoLocation.parse(fixture("ipwho_ok.json"));
        assertTrue(ok.isOk());
        assertEquals(55.7558, ok.value().lat, 1e-9);
        assertEquals(37.6173, ok.value().lon, 1e-9);
        assertEquals("Москва", ok.value().cityRu);
        assertEquals("RU", ok.value().country);
        assertEquals("Europe/Moscow", ok.value().timezoneId);
        assertEquals("DE", GeoLocation.parse(fixture("ipwho_de.json")).value().country);
        assertEquals("Europe/Berlin", GeoLocation.parse(fixture("ipwho_de.json")).value().timezoneId);
        assertFalse(GeoLocation.parse(fixture("ipwho_no_timezone.json")).isOk());
        assertFalse(GeoLocation.parse(fixture("ipwho_bad_timezone.json")).isOk());
        assertFalse(GeoLocation.parse(fixture("ipwho_no_country.json")).isOk());
        FetchResult<GeoLocation> fail = GeoLocation.parse(fixture("ipwho_fail.json"));
        assertFalse(fail.isOk());
        assertTrue(fail.error().contains("Invalid IP"));
        assertFalse(GeoLocation.parse(fixture("ipwho_missing.json")).isOk());
        assertFalse(GeoLocation.parse("{").isOk());
        assertFalse(GeoLocation.parse(null).isOk());
    }

    @Test public void display() {
        Weather w = new Weather(5.2, WeatherIcon.SUN, true, "", 8, -1.2, 0);
        WeatherDisplay none = WeatherDisplay.of(null, w);
        assertEquals(WeatherIcon.CLOUD_OFF, none.icon);
        assertEquals("Определить город", none.primary);
        assertEquals("", none.city);
        assertEquals("", none.range);
        assertEquals("", none.starfieldText());
        WeatherDisplay loading = WeatherDisplay.of(new GeoLocation(1, 2, "Омск", "RU", "Asia/Novosibirsk"), null);
        assertEquals(WeatherIcon.CLOUD_OFF, loading.icon);
        assertEquals("Омск", loading.primary);
        assertFalse(loading.hasWeather);
        WeatherDisplay full = WeatherDisplay.of(new GeoLocation(1, 2, "Новосибирск", "RU", "Asia/Novosibirsk"), w);
        assertEquals(WeatherIcon.SUN, full.icon);
        assertEquals("+5°", full.primary);
        assertEquals("Новосибирск", full.city);
        assertEquals("+8° / −1°", full.range);
        assertEquals("+5°   Новосибирск   +8° / −1°", full.starfieldText());
        WeatherDisplay noCity = WeatherDisplay.of(new GeoLocation(1, 2, "", "RU", "Asia/Novosibirsk"), w);
        assertEquals("", noCity.city);
        assertEquals("+5°   +8° / −1°", noCity.starfieldText());
        Weather rain = new Weather(5.2, WeatherIcon.CLOUD_RAIN, true, "дождь", 8, -1.2, 0);
        WeatherDisplay wet = WeatherDisplay.of(new GeoLocation(1, 2, "Омск", "RU", "Asia/Novosibirsk"), rain);
        assertEquals("+5° дождь", wet.primary);
        assertEquals("+5° дождь   Омск   +8° / −1°", wet.starfieldText());
    }
}
