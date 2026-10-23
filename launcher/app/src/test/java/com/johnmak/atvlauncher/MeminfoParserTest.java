package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.Test;

public class MeminfoParserTest {
    static String fixture(String name) throws IOException {
        try (InputStream in = MeminfoParserTest.class.getClassLoader().getResourceAsStream("stats/" + name)) {
            if (in == null) throw new IOException("missing fixture " + name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void realTvDumpTakesThePssBlockNotTheRssBlock() throws IOException {
        FetchResult<Map<String, Long>> r = MeminfoParser.parse(fixture("tv2_meminfo.txt"));
        assertTrue(r.isOk());
        Map<String, Long> m = r.value();
        assertEquals(Long.valueOf(119995), m.get("com.johnmak.atvlauncher")); // RSS block says 228260
        assertEquals(Long.valueOf(1497), m.get("gpuservice"));
        assertEquals(Long.valueOf(46211 + 47433), m.get("com.android.vending")); // ":background" folded in
        assertTrue(m.get("ru.kinopoisk.tv") > 0);
    }

    @Test
    public void sumsSubprocessesPerPackageAndSingleBlockIsUsedAsIs() {
        String dump = "version,1\ntime,1,2\noom,fore,10,N/A\n"
                + "proc,fore,com.a,1,100,N/A,a\nproc,cached,com.a:svc,2,50,N/A,e\nproc,cached,com.b,3,7,N/A,e\n"
                + "cat,Native,1,N/A\nram,1,2,3\n";
        Map<String, Long> m = MeminfoParser.parse(dump).value();
        assertEquals(Long.valueOf(150), m.get("com.a"));
        assertEquals(Long.valueOf(7), m.get("com.b"));
        assertEquals(2, m.size());
    }

    @Test
    public void rejectsEmptyMalformedAndProcFreeOutput() {
        assertFalse(MeminfoParser.parse(null).isOk());
        assertFalse(MeminfoParser.parse("").isOk());
        assertFalse(MeminfoParser.parse("version,1\ntime,1,2\n").isOk());
        assertFalse(MeminfoParser.parse("proc,fore,com.a,1\n").isOk());
        assertFalse(MeminfoParser.parse("proc,fore,com.a,1,notanumber,N/A,a\n").isOk());
        assertFalse(MeminfoParser.parse("proc,fore,com.a,1,-5,N/A,a\n").isOk());
        assertFalse(MeminfoParser.parse("proc,fore,,1,5,N/A,a\n").isOk());
    }
}
