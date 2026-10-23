package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class AppDescriptionsTest {
    @Test
    public void realAssetParsesWithoutErrors() throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("src/main/assets/app_descriptions.tsv")), StandardCharsets.UTF_8);
        FetchResult<AppDescriptions> r = AppDescriptions.parse(text);
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertTrue(r.value().describe("com.google.android.gms", true).contains("Сервисы Google"));
        assertTrue(r.value().size() > 100);
    }

    @Test
    public void parsesEntriesSkippingCommentsAndBlankLines() {
        FetchResult<AppDescriptions> r = AppDescriptions.parse("# c\n\na.b\tПервое\r\nc.d\tВторое\n");
        assertTrue(r.isOk());
        assertEquals(2, r.value().size());
        assertEquals("Первое", r.value().describe("a.b", false));
        assertEquals("Второе", r.value().describe("c.d", true));
    }

    @Test
    public void fallbacksForUnknownPackages() {
        AppDescriptions d = AppDescriptions.parse("a.b\tX\n").value();
        assertEquals("Системный компонент производителя", d.describe("z.z", true));
        assertEquals("Установленное приложение", d.describe("z.z", false));
    }

    @Test
    public void strictErrorsCarryLineNumbers() {
        assertError("a.b\tX\na.b\tY\n", "line 2");
        assertError("# c\na.b X\n", "line 2");
        assertError("bad name\tX\n", "line 1");
        assertError("single\tX\n", "line 1");
        assertError("a.b\t\n", "line 1");
        assertError("a.b\t   \n", "line 1");
        assertError(null, "null");
    }

    private static void assertError(String text, String contains) {
        FetchResult<AppDescriptions> r = AppDescriptions.parse(text);
        assertFalse(r.isOk());
        assertTrue(r.error(), r.error().contains(contains));
    }
}
