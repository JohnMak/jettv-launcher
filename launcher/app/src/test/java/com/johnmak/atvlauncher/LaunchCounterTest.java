package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LaunchCounterTest {
    @Test
    public void countsTransitionsIntoAnAppNotEveryActivity() {
        LaunchCounter c = new LaunchCounter();
        for (String p : new String[]{"home", "kino", "kino", "kino", "home", "kino", "lampa", "kino"}) {
            c.onForeground(p);
        }
        assertEquals(3L, (long) c.counts().get("kino"));
        assertEquals(1L, (long) c.counts().get("lampa"));
        assertEquals(2L, (long) c.counts().get("home"));
        assertNull(c.counts().get("other"));
    }
}
