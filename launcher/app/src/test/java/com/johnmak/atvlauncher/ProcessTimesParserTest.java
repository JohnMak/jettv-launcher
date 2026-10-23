package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.Test;

public class ProcessTimesParserTest {
    private static final String HEADER = "  PID     TIME+ NAME                       \n";

    @Test
    public void realTvOutput() throws IOException {
        FetchResult<Map<String, Long>> r = ProcessTimesParser.parse(MeminfoParserTest.fixture("tv2_ps.txt"));
        assertTrue(r.isOk());
        Map<String, Long> m = r.value();
        assertEquals(Long.valueOf(10_950 + 2_370), m.get("init"));
        assertEquals(Long.valueOf((60 * 60 + 8) * 1000L + 650), m.get("com.johnmak.atvlauncher"));
        assertEquals(Long.valueOf(250), m.get("[kthreadd]"));
        assertEquals(Long.valueOf(271_670 + 6_440), m.get("com.android.vending")); // ":background" folded in
    }

    @Test
    public void formatsAndSummingAndEdges() {
        String out = HEADER
                + "    1   0:10.95 com.a\n"
                + "    2   1:00.05 com.a:svc\n"
                + "    3 1:02:03.04 com.b\n"
                + "    4 2-03:04:05.06 com.c\n"
                + "\n";
        Map<String, Long> m = ProcessTimesParser.parse(out).value();
        assertEquals(Long.valueOf(10_950 + 60_050), m.get("com.a"));
        assertEquals(Long.valueOf(((1 * 60 + 2) * 60 + 3) * 1000L + 40), m.get("com.b"));
        assertEquals(Long.valueOf((((2 * 24 + 3) * 60 + 4) * 60 + 5) * 1000L + 60), m.get("com.c"));
    }

    @Test
    public void rejectsBadInput() {
        assertFalse(ProcessTimesParser.parse(null).isOk());
        assertFalse(ProcessTimesParser.parse("").isOk());
        assertFalse(ProcessTimesParser.parse("    1   0:10.95 init\n").isOk()); // no header
        assertFalse(ProcessTimesParser.parse(HEADER + "    1   0:10 init\n").isOk()); // no centiseconds
        assertFalse(ProcessTimesParser.parse(HEADER + "    x   0:10.95 init\n").isOk());
        assertFalse(ProcessTimesParser.parse(HEADER + "    1   0:10.95\n").isOk());
        assertFalse(ProcessTimesParser.parse(HEADER + "    1   0:75.95 init\n").isOk());
    }
}
