package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GmpfParserTest {
    private static int ok(String raw) {
        FetchResult<Integer> r = GmpfParser.parseValue(raw);
        assertTrue(raw, r.isOk());
        return r.value();
    }

    @Test
    public void parsesRealOutputs() {
        assertEquals(3, ok("return_data : {3 }"));
        assertEquals(10, ok("return_data : {10}\n"));
        assertEquals(1, ok("return_data : {1 }\r\n"));
        assertEquals(0, ok("return_data : {0 }"));
    }

    @Test
    public void toleratesWhitespaceAndSurroundingText() {
        assertEquals(6, ok("  \nreturn_data:{ 6 }  \n"));
        assertEquals(7, ok("calling getDlpLumensLevel\nreturn_data : {7 }\ndone\n"));
        assertEquals(2, ok("return_data   :   {  2  }"));
    }

    @Test
    public void rejectsNullEmptyAndGarbage() {
        for (String raw : new String[] {null, "", "  \n", "garbage", "return_data", "return_data : {}",
                "return_data : {abc }", "return_data : {1.5 }", "return_data : {3"}) {
            FetchResult<Integer> r = GmpfParser.parseValue(raw);
            assertFalse(String.valueOf(raw), r.isOk());
            assertFalse(r.error().isEmpty());
        }
    }

    @Test
    public void shellErrorsAreDescriptive() {
        FetchResult<Integer> nf = GmpfParser.parseValue("/system/bin/sh: gmpfUnit: not found");
        assertFalse(nf.isOk());
        assertTrue(nf.error().contains("not found")); // raw output is quoted; classification is ShellErrorClassifier's
        FetchResult<Integer> ina = GmpfParser.parseValue("/system/bin/sh: gmpfUnit: inaccessible or not found");
        assertFalse(ina.isOk());
        assertTrue(ina.error().contains("inaccessible"));
    }
}
