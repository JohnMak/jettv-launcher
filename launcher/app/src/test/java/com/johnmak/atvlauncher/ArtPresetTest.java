package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ArtPresetTest {
    private static ArtManifest.Entry entryWith(long id, String... categories) {
        List<String> cats = new ArrayList<>();
        Collections.addAll(cats, categories);
        return new ArtManifest.Entry(id, "T" + id, "1891", Collections.unmodifiableList(cats),
                id + "-abcdef012345.jpg", "0123456789abcdef0123456789abcdef01234567");
    }

    @Test public void filterAllMatchesEveryEntryRegardlessOfCategories() {
        List<ArtManifest.Entry> entries = java.util.Arrays.asList(
                entryWith(1, "era:19c"), entryWith(2), entryWith(3, "movement:avantgarde"));
        assertEquals(3, ArtPreset.ALL.filter(entries).size());
    }

    @Test public void filterEachPresetMatchesOnlyItsTag() {
        ArtManifest.Entry peredvizhniki = entryWith(1, "movement:peredvizhniki");
        ArtManifest.Entry era19c = entryWith(2, "era:19c");
        ArtManifest.Entry avantgarde = entryWith(3, "movement:avantgarde");
        ArtManifest.Entry untagged = entryWith(4, "era:empire");
        List<ArtManifest.Entry> entries = java.util.Arrays.asList(peredvizhniki, era19c, avantgarde, untagged);

        assertEquals(Collections.singletonList(peredvizhniki), ArtPreset.PEREDVIZHNIKI.filter(entries));
        assertEquals(Collections.singletonList(era19c), ArtPreset.ERA_19C.filter(entries));
        assertEquals(Collections.singletonList(avantgarde), ArtPreset.AVANTGARDE.filter(entries));
    }

    @Test public void filterReturnsEmptyListNeverThrowsOnNoMatch() {
        List<ArtManifest.Entry> entries = Collections.singletonList(entryWith(1, "era:empire"));
        assertTrue(ArtPreset.PEREDVIZHNIKI.filter(entries).isEmpty());
    }

    @Test public void fromKeyRoundTripsEveryPreset() {
        for (ArtPreset p : ArtPreset.values()) {
            assertSame(p, ArtPreset.fromKey(p.key));
        }
    }

    @Test public void fromKeyUnknownOrNullFallsBackToAll() {
        assertSame(ArtPreset.ALL, ArtPreset.fromKey("no-such-key"));
        assertSame(ArtPreset.ALL, ArtPreset.fromKey(null));
        assertSame(ArtPreset.ALL, ArtPreset.fromKey(""));
    }

    // ---- shared effective-preset resolver (D11) --------------------------------------------------

    @Test public void effectiveReturnsStoredPresetWhenAvailable() {
        List<ArtPreset> available = java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.PEREDVIZHNIKI);
        assertSame(ArtPreset.PEREDVIZHNIKI, ArtPreset.effective(ArtPreset.PEREDVIZHNIKI.key, available));
    }

    @Test public void effectiveFallsBackToAllWhenStoredPresetIsHidden() {
        // stored preset exists in the catalog but its pool is currently below threshold -> not in `available`
        List<ArtPreset> available = Collections.singletonList(ArtPreset.ALL);
        assertSame(ArtPreset.ALL, ArtPreset.effective(ArtPreset.AVANTGARDE.key, available));
    }

    @Test public void effectiveFallsBackToAllWhenAvailableIsEmpty() {
        assertSame(ArtPreset.ALL, ArtPreset.effective(ArtPreset.PEREDVIZHNIKI.key, Collections.emptyList()));
        assertSame(ArtPreset.ALL, ArtPreset.effective(ArtPreset.ALL.key, Collections.emptyList()));
    }

    @Test public void effectiveNeverMutatesOrLosesTheStoredKey() {
        // the resolver is pure: calling it repeatedly with a hidden stored key never "forgets" it — the caller is
        // free to keep persisting the same storedKey, and the resolver keeps rendering ALL until the pool recovers.
        List<ArtPreset> hidden = Collections.singletonList(ArtPreset.ALL);
        List<ArtPreset> recovered = java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.AVANTGARDE);
        String storedKey = ArtPreset.AVANTGARDE.key;

        assertSame(ArtPreset.ALL, ArtPreset.effective(storedKey, hidden));
        assertSame(ArtPreset.AVANTGARDE, ArtPreset.effective(storedKey, recovered)); // same storedKey, unchanged
    }

    @Test public void effectiveWithUnknownStoredKeyIsAll() {
        List<ArtPreset> available = java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.PEREDVIZHNIKI);
        assertSame(ArtPreset.ALL, ArtPreset.effective("not-a-real-key", available));
    }

    // ---- available-preset cycling (Step 7): wrap in both directions over 0/1/2/3-element sets ------

    @Test public void cycleEmptySetIsDisabledNoWrapNoCrash() {
        // 0 elements = the category row is disabled; cycling either way is a no-op, never an index crash.
        List<ArtPreset> none = Collections.emptyList();
        assertSame(ArtPreset.ALL, ArtPreset.cycle(none, ArtPreset.ALL, 1));
        assertSame(ArtPreset.ALL, ArtPreset.cycle(none, ArtPreset.ALL, -1));
    }

    @Test public void cycleSingleElementSetStaysPut() {
        // 1 element = nothing to cycle to; the lone preset is returned unchanged in both directions.
        List<ArtPreset> one = Collections.singletonList(ArtPreset.ALL);
        assertSame(ArtPreset.ALL, ArtPreset.cycle(one, ArtPreset.ALL, 1));
        assertSame(ArtPreset.ALL, ArtPreset.cycle(one, ArtPreset.ALL, -1));
    }

    @Test public void cycleTwoElementSetWrapsBothDirections() {
        List<ArtPreset> two = java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.PEREDVIZHNIKI);
        assertSame(ArtPreset.PEREDVIZHNIKI, ArtPreset.cycle(two, ArtPreset.ALL, 1));
        assertSame(ArtPreset.ALL, ArtPreset.cycle(two, ArtPreset.PEREDVIZHNIKI, 1));          // forward wrap
        assertSame(ArtPreset.PEREDVIZHNIKI, ArtPreset.cycle(two, ArtPreset.ALL, -1));         // backward wrap
        assertSame(ArtPreset.ALL, ArtPreset.cycle(two, ArtPreset.PEREDVIZHNIKI, -1));
    }

    @Test public void cycleThreeElementSetWrapsBothDirections() {
        List<ArtPreset> three = java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.PEREDVIZHNIKI, ArtPreset.ERA_19C);
        // forward: ALL -> PEREDVIZHNIKI -> ERA_19C -> ALL
        assertSame(ArtPreset.PEREDVIZHNIKI, ArtPreset.cycle(three, ArtPreset.ALL, 1));
        assertSame(ArtPreset.ERA_19C, ArtPreset.cycle(three, ArtPreset.PEREDVIZHNIKI, 1));
        assertSame(ArtPreset.ALL, ArtPreset.cycle(three, ArtPreset.ERA_19C, 1));
        // backward: ALL -> ERA_19C -> PEREDVIZHNIKI -> ALL
        assertSame(ArtPreset.ERA_19C, ArtPreset.cycle(three, ArtPreset.ALL, -1));
        assertSame(ArtPreset.PEREDVIZHNIKI, ArtPreset.cycle(three, ArtPreset.ERA_19C, -1));
        assertSame(ArtPreset.ALL, ArtPreset.cycle(three, ArtPreset.PEREDVIZHNIKI, -1));
    }

    @Test public void cycleDefensivelyRestartsWhenCurrentNotInAvailable() {
        // current absent from a 2+ set (should not happen via effective(), but must never crash): start at the first.
        List<ArtPreset> two = java.util.Arrays.asList(ArtPreset.ALL, ArtPreset.PEREDVIZHNIKI);
        assertSame(ArtPreset.ALL, ArtPreset.cycle(two, ArtPreset.AVANTGARDE, 1));
    }
}
