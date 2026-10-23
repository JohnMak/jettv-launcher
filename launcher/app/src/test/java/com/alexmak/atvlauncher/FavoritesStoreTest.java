package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

public class FavoritesStoreTest {
    private static final class MemoryStorage implements FavoritesStore.Storage {
        String value;

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String v) {
            value = v;
        }
    }

    private static final String[] SEED = {"a", "b", "c", "d"};

    @Test
    public void firstRunIsSeededAndPersisted() {
        MemoryStorage s = new MemoryStorage();
        FavoritesStore store = new FavoritesStore(s, SEED);
        assertEquals(Arrays.asList("a", "b", "c", "d"), store.list());
        assertNotNull(s.value);
        assertEquals(Arrays.asList("a", "b", "c", "d"), new FavoritesStore(s, new String[]{"x"}).list());
    }

    @Test
    public void emptyListSurvivesRestartWithoutReseeding() {
        MemoryStorage s = new MemoryStorage();
        FavoritesStore store = new FavoritesStore(s, SEED);
        for (String p : SEED) store.remove(p);
        assertTrue(new FavoritesStore(s, SEED).list().isEmpty());
    }

    @Test
    public void addAppendsOnceAndRemoveDrops() {
        FavoritesStore store = new FavoritesStore(new MemoryStorage(), SEED);
        assertTrue(store.add("e"));
        assertFalse(store.add("e"));
        assertEquals(Arrays.asList("a", "b", "c", "d", "e"), store.list());
        assertTrue(store.remove("b"));
        assertFalse(store.remove("b"));
        assertEquals(Arrays.asList("a", "c", "d", "e"), store.list());
        assertTrue(store.contains("a"));
        assertFalse(store.contains("b"));
    }

    @Test
    public void moveShiftsAndClampsAtEnds() {
        FavoritesStore store = new FavoritesStore(new MemoryStorage(), SEED);
        assertEquals(2, store.move("b", 1));
        assertEquals(Arrays.asList("a", "c", "b", "d"), store.list());
        assertEquals(0, store.move("a", -1));
        assertEquals(3, store.move("d", 1));
        assertEquals(-1, store.move("zzz", 1));
        assertEquals(Arrays.asList("a", "c", "b", "d"), store.list());
    }

    @Test
    public void retainOnlyDropsUninstalledAndPersists() {
        MemoryStorage s = new MemoryStorage();
        FavoritesStore store = new FavoritesStore(s, SEED);
        store.retainOnly(new HashSet<>(Arrays.asList("a", "c", "zzz")));
        assertEquals(Arrays.asList("a", "c"), store.list());
        assertEquals(Arrays.asList("a", "c"), new FavoritesStore(s, SEED).list());
        store.retainOnly(Collections.singleton("a"));
        assertEquals(Collections.singletonList("a"), store.list());
    }
}
