package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CenterKeyTest {
    @Test
    public void shortPressFiresOnUp() {
        CenterKey k = new CenterKey();
        assertEquals(CenterKey.Press.NONE, k.onKey(true, 0));
        assertEquals(CenterKey.Press.SHORT, k.onKey(false, 0));
    }

    @Test
    public void longPressFiresOnceOnFirstRepeatAndSuppressesUp() {
        CenterKey k = new CenterKey();
        k.onKey(true, 0);
        assertEquals(CenterKey.Press.LONG, k.onKey(true, 1));
        assertEquals(CenterKey.Press.NONE, k.onKey(true, 2));
        assertEquals(CenterKey.Press.NONE, k.onKey(false, 0));
        k.onKey(true, 0);
        assertEquals(CenterKey.Press.SHORT, k.onKey(false, 0));
    }

    @Test
    public void strayUpAndRepeatWithoutDownAreIgnored() {
        CenterKey k = new CenterKey();
        assertEquals(CenterKey.Press.NONE, k.onKey(false, 0));
        assertEquals(CenterKey.Press.NONE, k.onKey(true, 1));
    }
}
