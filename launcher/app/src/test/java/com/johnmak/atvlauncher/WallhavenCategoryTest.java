package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The static Wallhaven category catalog (plan B2, bead j9m): the category→{@code q=} mapping, the legacy-key
 * fallback (including every key from the old, untouched {@link ArtPreset} scheme), and cycling. Deliberately a
 * SEPARATE type from {@link ArtPreset} (owner decision 2026-10-28, Option B) — {@link ArtPresetTest} and
 * {@link ArtManifestTest} are untouched and still exercise the old taxonomy.
 */
public class WallhavenCategoryTest {

    // ---- category -> Wallhaven query mapping -----------------------------------------------------

    @Test public void everyCategoryHasADistinctNonEmptyQueryTag() {
        java.util.Set<String> queries = new java.util.HashSet<>();
        for (WallhavenCategory c : WallhavenCategory.values()) {
            assertNotNull(c.query);
            assertFalse("query must not be blank: " + c, c.query.trim().isEmpty());
            assertTrue("query tags must be distinct, duplicate: " + c.query, queries.add(c.query));
        }
    }

    @Test public void everyCategoryHasADistinctNonEmptyPersistedKey() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (WallhavenCategory c : WallhavenCategory.values()) {
            assertNotNull(c.key);
            assertFalse("key must not be blank: " + c, c.key.trim().isEmpty());
            assertTrue("persisted keys must be distinct, duplicate: " + c.key, keys.add(c.key));
        }
    }

    @Test public void expectedSixCategoriesWithTheirQueryTags() {
        assertEquals("nature", WallhavenCategory.NATURE.query);
        assertEquals("landscape", WallhavenCategory.LANDSCAPES.query);
        assertEquals("space", WallhavenCategory.SPACE_SKY.query);
        assertEquals("city", WallhavenCategory.CITY.query);
        assertEquals("abstract", WallhavenCategory.ABSTRACT.query);
        assertEquals("minimal", WallhavenCategory.MINIMAL.query);
        assertEquals(6, WallhavenCategory.values().length);
    }

    /** Every category's query builds a valid, allowlisted, General+SFW-only search URL (plan B3: categories=100 +
     * purity=100 on EVERY request, never widened — none of these six categories implies people/anime). */
    @Test public void everyCategoryBuildsAGeneralSfwOnlySearchUrl() {
        for (WallhavenCategory c : WallhavenCategory.values()) {
            String url = WallhavenSource.searchUrl(c.query, "seed1", 1);
            assertTrue(c + ": categories=100 (General)", url.contains("categories=100"));
            assertTrue(c + ": purity=100 (SFW)", url.contains("purity=100"));
            assertTrue(c + ": carries its own q= tag", url.contains("&q=" + c.query));
            assertTrue(c + ": stays within the allowlist", WallhavenHosts.check(url).isOk());
        }
    }

    // ---- fromKey / legacy-key fallback --------------------------------------------------------

    @Test public void fromKeyRoundTripsEveryCategory() {
        for (WallhavenCategory c : WallhavenCategory.values()) {
            assertSame(c, WallhavenCategory.fromKey(c.key));
        }
    }

    @Test public void fromKeyUnknownOrNullFallsBackToDefault() {
        assertSame(WallhavenCategory.DEFAULT, WallhavenCategory.fromKey("no-such-key"));
        assertSame(WallhavenCategory.DEFAULT, WallhavenCategory.fromKey(null));
        assertSame(WallhavenCategory.DEFAULT, WallhavenCategory.fromKey(""));
    }

    @Test public void defaultIsNature() {
        assertSame(WallhavenCategory.NATURE, WallhavenCategory.DEFAULT);
    }

    /** Every key from the old pre-cutover {@link ArtPreset} scheme must resolve gracefully (never crash) on an
     * upgrading install — it simply lands on {@link WallhavenCategory#DEFAULT} until the user picks a new category. */
    @Test public void everyLegacyArtPresetKeyFallsBackToDefaultWithoutCrashing() {
        for (ArtPreset legacy : ArtPreset.values()) {
            assertSame("legacy key " + legacy.key + " must not crash", WallhavenCategory.DEFAULT,
                    WallhavenCategory.fromKey(legacy.key));
        }
    }

    // ---- effective (degenerates to fromKey: the catalog is always fully available) -----------

    @Test public void effectiveMirrorsFromKeyForARealKey() {
        assertSame(WallhavenCategory.CITY, WallhavenCategory.effective(WallhavenCategory.CITY.key));
    }

    @Test public void effectiveMirrorsFromKeyForALegacyOrUnknownKey() {
        assertSame(WallhavenCategory.DEFAULT, WallhavenCategory.effective("peredvizhniki"));
        assertSame(WallhavenCategory.DEFAULT, WallhavenCategory.effective(null));
    }

    // ---- cycle: wraps both directions over all six fixed members ------------------------------

    @Test public void cycleForwardStepsThroughAllSixAndWraps() {
        WallhavenCategory c = WallhavenCategory.NATURE;
        WallhavenCategory[] all = WallhavenCategory.values();
        for (int i = 1; i < all.length; i++) {
            c = WallhavenCategory.cycle(c, 1);
            assertSame(all[i], c);
        }
        assertSame(all[0], WallhavenCategory.cycle(c, 1)); // wraps back to the first
    }

    @Test public void cycleBackwardWrapsFromTheFirstToTheLast() {
        WallhavenCategory[] all = WallhavenCategory.values();
        assertSame(all[all.length - 1], WallhavenCategory.cycle(all[0], -1));
    }

    @Test public void cycleIsReversible() {
        for (WallhavenCategory c : WallhavenCategory.values()) {
            assertSame(c, WallhavenCategory.cycle(WallhavenCategory.cycle(c, 1), -1));
            assertSame(c, WallhavenCategory.cycle(WallhavenCategory.cycle(c, -1), 1));
        }
    }
}
