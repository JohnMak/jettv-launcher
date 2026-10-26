package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.List;

/**
 * The single-select preset catalog (D1) plus the shared effective-preset resolver (D11). Pure and stateless:
 * {@link #filter}, {@link #fromKey}, and {@link #effective} touch no shared/mutable state, so both the settings
 * Activity and the rendering path (launcher + Dream, which load settings independently — Point A) call the same
 * functions and always agree.
 */
enum ArtPreset {
    ALL("all", null),
    PEREDVIZHNIKI("peredvizhniki", "movement:peredvizhniki"),
    ERA_19C("era_19c", "era:19c"),
    AVANTGARDE("avantgarde", "movement:avantgarde");

    /** A preset is offered only when its vetted pool reaches this count (D6); {@link #ALL} is exempt. */
    static final int MIN_PRESET_WORKS = 4;

    /** The persisted value ({@code art_preset}); stable across releases. */
    final String key;
    private final String tag;

    ArtPreset(String key, String tag) {
        this.key = key;
        this.tag = tag;
    }

    /** The subset of {@code entries} matching this preset; {@link #ALL} matches every entry (a fresh copy). */
    List<ArtManifest.Entry> filter(List<ArtManifest.Entry> entries) {
        if (tag == null) return new ArrayList<>(entries);
        List<ArtManifest.Entry> matched = new ArrayList<>();
        for (ArtManifest.Entry e : entries) {
            if (e.categories.contains(tag)) matched.add(e);
        }
        return matched;
    }

    /** The preset whose {@link #key} equals {@code key}, or {@link #ALL} when {@code key} is null/unrecognized. */
    static ArtPreset fromKey(String key) {
        if (key != null) {
            for (ArtPreset p : values()) {
                if (p.key.equals(key)) return p;
            }
        }
        return ALL;
    }

    /**
     * The shared effective-preset resolver (D11): the stored preset when it is a member of {@code available}, else
     * {@link #ALL}. Pure — never mutates the stored preference; a stored-but-hidden preset (its pool fell below
     * {@link #MIN_PRESET_WORKS}, or the whole catalog is temporarily unavailable) renders as {@link #ALL} while the
     * stored key survives untouched until the user explicitly cycles to a new value.
     */
    static ArtPreset effective(String storedKey, List<ArtPreset> available) {
        ArtPreset stored = fromKey(storedKey);
        return available.contains(stored) ? stored : ALL;
    }
}
