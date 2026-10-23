package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;

public class SettingsImportTest {
    private static final String TOKEN = "0a1b2c3d-4e5f-6789-abcd-ef0123456789";

    private static String doc(String prefs) {
        return "{\"version\":1,\"prefs\":" + prefs + "}";
    }

    private static String cell(String type, String value) {
        return "{\"type\":\"" + type + "\",\"value\":" + value + "}";
    }

    @Test
    public void validFullImportIsParsedWithTypes() {
        String json = doc("{"
                + "\"favorites\":{\"packages\":" + cell("string", "\"a,b\"") + "},"
                + "\"launcher_settings\":{"
                + "\"screensaver_enabled\":" + cell("bool", "true") + ","
                + "\"screensaver_delay_min\":" + cell("int", "5") + ","
                + "\"screensaver_type\":" + cell("string", "\"CLOCK\"") + ","
                + "\"dim_enabled\":" + cell("bool", "false") + ","
                + "\"dim_delay_sec\":" + cell("int", "30") + ","
                + "\"vpn_button_visible\":" + cell("bool", "true") + ","
                + "\"vpn_package\":" + cell("string", "\"x.y\"") + ","
                + "\"ui_scale_percent\":" + cell("int", "100") + ","
                + "\"preview_scale_percent\":" + cell("int", "90") + ","
                + "\"feed_excluded_recommendations\":" + cell("stringset", "[\"p.q\",\"r.s\"]") + ","
                + "\"feed_excluded_continue\":" + cell("stringset", "[]") + "},"
                + "\"kinopoisk\":{\"token\":" + cell("string", "\" " + TOKEN + "\"") + "},"
                + "\"weather\":{\"g2_lat\":" + cell("long", "4632000000000000000") + ","
                + "\"g2_lon\":" + cell("long", "7") + ",\"g2_city\":" + cell("string", "\"Москва\"") + ","
                + "\"g2_country\":" + cell("string", "\"RU\"") + ",\"g2_tz\":" + cell("string", "\"Europe/Moscow\"") + "}}");
        FetchResult<SettingsImport.ImportPlan> r = SettingsImport.parse(json);
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertEquals(18, r.value().entries.size());
        for (SettingsImport.Entry e : r.value().entries) {
            assertEquals(SettingsImport.WHITELIST.get(e.file).get(e.key), e.type);
            if (e.key.equals("token")) assertEquals(TOKEN, e.value);
            if (e.key.equals("g2_lat")) assertEquals(4632000000000000000L, e.value);
            if (e.key.equals("g2_lon")) assertEquals(7L, e.value);
            if (e.key.equals("feed_excluded_recommendations")) assertEquals(new HashSet<>(Arrays.asList("p.q", "r.s")), e.value);
        }
    }

    @Test
    public void emptyPrefsIsAnEmptyPlan() {
        FetchResult<SettingsImport.ImportPlan> r = SettingsImport.parse(doc("{}"));
        assertTrue(r.isOk());
        assertTrue(r.value().entries.isEmpty());
    }

    @Test
    public void unknownFileKeyOrTypeRejectsEverything() {
        assertFalse(SettingsImport.parse(doc("{\"other\":{}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"weather\":{\"w3_temp\":" + cell("long", "1") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"favorites\":{\"nope\":" + cell("string", "\"x\"") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"favorites\":{\"packages\":" + cell("blob", "\"x\"") + "}}")).isOk());
        // one bad entry poisons an otherwise valid file
        assertFalse(SettingsImport.parse(doc("{\"favorites\":{\"packages\":" + cell("string", "\"x\"") + "},"
                + "\"weather\":{\"g2_city\":" + cell("string", "\"c\"") + ",\"zzz\":" + cell("string", "\"c\"") + "}}")).isOk());
    }

    @Test
    public void wrongTypeOrValueIsRejected() {
        assertFalse(SettingsImport.parse(doc("{\"launcher_settings\":{\"dim_delay_sec\":" + cell("string", "\"5\"") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"launcher_settings\":{\"dim_delay_sec\":" + cell("int", "\"5\"") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"launcher_settings\":{\"dim_delay_sec\":" + cell("int", "5.5") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"launcher_settings\":{\"dim_enabled\":" + cell("bool", "1") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"weather\":{\"g2_lat\":" + cell("int", "1") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"launcher_settings\":{\"feed_excluded_continue\":" + cell("stringset", "[1]") + "}}")).isOk());
    }

    @Test
    public void brokenJsonIsRejected() {
        assertFalse(SettingsImport.parse("{\"version\":1,").isOk());
        assertFalse(SettingsImport.parse("").isOk());
        assertFalse(SettingsImport.parse("[]").isOk());
    }

    @Test
    public void badTokenIsRejected() {
        assertFalse(SettingsImport.parse(doc("{\"kinopoisk\":{\"token\":" + cell("string", "\"not-a-token\"") + "}}")).isOk());
        assertFalse(SettingsImport.parse(doc("{\"kinopoisk\":{\"token\":" + cell("string", "\"\"") + "}}")).isOk());
    }

    @Test
    public void versionMustBeExactlyOne() {
        assertFalse(SettingsImport.parse("{\"prefs\":{}}").isOk());
        assertFalse(SettingsImport.parse("{\"version\":2,\"prefs\":{}}").isOk());
        assertFalse(SettingsImport.parse("{\"version\":\"1\",\"prefs\":{}}").isOk());
        assertFalse(SettingsImport.parse("{\"version\":1}").isOk());
    }
}
