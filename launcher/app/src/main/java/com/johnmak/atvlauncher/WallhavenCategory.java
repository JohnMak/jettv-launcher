package com.johnmak.atvlauncher;

/**
 * The static Wallhaven category catalog for the ART screensaver's «Категория» settings row (plan B2, bead j9m).
 * Pure and stateless, mirroring {@link ArtPreset}'s {@code fromKey}/{@code effective}/{@code cycle} pattern — but
 * simplified: unlike the old manifest catalog, this list is fixed at compile time (no network fetch, no
 * availability gate), so every member is always offered. The settings row selects and persists a member by
 * {@link #key}; {@code ArtScreensaverView}'s {@code WallhavenSession} resolves the stored key once and reads
 * {@link #query} to build the search URL (plan B1/B2: {@code WallhavenSource#searchUrl} appends it as
 * {@code &q=...}; {@code categories=100}/{@code purity=100} are GLOBAL defaults {@code WallhavenSource} builds into
 * every request — no category here ever widens them, since none of the six implies people/anime).
 *
 * <p>Deliberately a NEW, separate type from {@link ArtPreset} (owner decision 2026-10-28, Option B): {@link ArtPreset}
 * and {@link ArtManifest} keep their own movement/era taxonomy untouched, still compiling with their existing tests,
 * until the later cutover bead deletes {@code ArtPreset}/{@code ArtManifest}/{@code ArtCache}/{@code ArtSource}
 * together. Entangling the two taxonomies in one enum would have required a sentinel {@code tag} value purely to
 * dodge {@link ArtManifest#availablePresets()}'s matching — a hack this plan rejects.
 */
enum WallhavenCategory {
    NATURE("nature", "nature"),
    LANDSCAPES("landscapes", "landscape"),
    SPACE_SKY("space_sky", "space"),
    CITY("city", "city"),
    ABSTRACT("abstract", "abstract"),
    MINIMAL("minimal", "minimal");

    /** The persisted value (shared pref key {@code art_preset}, reused verbatim from the old scheme); stable across
     * releases. */
    final String key;
    /** The Wallhaven {@code q=} search term for this category. */
    final String query;

    WallhavenCategory(String key, String query) {
        this.key = key;
        this.query = query;
    }

    /** The default/fallback member — also what an unrecognized or legacy key resolves to. */
    static final WallhavenCategory DEFAULT = NATURE;

    /**
     * The category whose {@link #key} equals {@code key}, or {@link #DEFAULT} when null/unrecognized — INCLUDING
     * every key from the old {@link ArtPreset} scheme ({@code "all"}/{@code "peredvizhniki"}/{@code "era_19c"}/
     * {@code "avantgarde"}), so an install upgrading from the old category scheme never crashes on its previously
     * stored preference; it simply lands on {@link #DEFAULT} until the user picks a new category explicitly.
     */
    static WallhavenCategory fromKey(String key) {
        if (key != null) {
            for (WallhavenCategory c : values()) {
                if (c.key.equals(key)) return c;
            }
        }
        return DEFAULT;
    }

    /**
     * The category to render/query for a stored key. The catalog is static (always fully available, unlike the old
     * manifest-gated {@link ArtPreset#effective}), so this degenerates to {@link #fromKey} — kept as a separate,
     * named call for symmetry with the settings row's read path.
     */
    static WallhavenCategory effective(String storedKey) {
        return fromKey(storedKey);
    }

    /**
     * The category reached by stepping {@code step} (+1 next / −1 previous) from {@code current}, wrapping at both
     * ends. The row is always cyclable — six fixed members, no empty/loading state to gate on.
     */
    static WallhavenCategory cycle(WallhavenCategory current, int step) {
        WallhavenCategory[] all = values();
        int i = current.ordinal();
        return all[((i + step) % all.length + all.length) % all.length];
    }
}
