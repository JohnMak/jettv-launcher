package com.johnmak.atvlauncher;

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
    public void equalsAndHashCodeByValue() {
        assertEquals(LampLevel.of(3).value(), LampLevel.of(3).value());
        assertEquals(LampLevel.of(3).value().hashCode(), LampLevel.of(3).value().hashCode());
        assertNotEquals(LampLevel.of(3).value(), LampLevel.of(4).value());
    }
}
