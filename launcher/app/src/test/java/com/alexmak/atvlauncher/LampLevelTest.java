package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LampLevelTest {
    @Test
    public void acceptsRangeBoundsAndRejectsOutside() {
        for (int v = LampLevel.MIN_LEVEL; v <= LampLevel.MAX_LEVEL; v++) {
            FetchResult<LampLevel> r = LampLevel.of(v);
            assertTrue(r.isOk());
            assertEquals(v, r.value().value());
        }
        assertFalse(LampLevel.of(0).isOk());
        assertFalse(LampLevel.of(11).isOk());
        assertFalse(LampLevel.of(Integer.MIN_VALUE).isOk());
        assertTrue(LampLevel.of(11).error().contains("11"));
    }

    @Test
    public void stepIsClampedToRange() {
        LampLevel five = LampLevel.of(5).value();
        assertEquals(6, five.step(1).value());
        assertEquals(4, five.step(-1).value());
        assertEquals(5, five.step(0).value());
        assertEquals(10, five.step(100).value());
        assertEquals(1, five.step(-100).value());
        assertEquals(10, LampLevel.of(10).value().step(1).value());
        assertEquals(1, LampLevel.of(1).value().step(Integer.MIN_VALUE).value());
        assertEquals(10, LampLevel.of(5).value().step(Integer.MAX_VALUE).value());
    }

    @Test
    public void equalsAndHashCodeByValue() {
        assertEquals(LampLevel.of(3).value(), LampLevel.of(3).value());
        assertEquals(LampLevel.of(3).value().hashCode(), LampLevel.of(3).value().hashCode());
        assertNotEquals(LampLevel.of(3).value(), LampLevel.of(4).value());
    }
}
