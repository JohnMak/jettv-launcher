package com.johnmak.atvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * The slim published manifest ({@code https://art.jmwork.ru/manifest.json}): a top-level {@code base_url} plus entries
 * {@code {id, title, year, categories, file, sha1}}.
 *
 * <p>Step 4 (bead fs8): the {@link Entry} record, structural {@link #parse(byte[])}, and the
 * {@link #isValidCandidate(byte[])} gate that {@link ArtSource} consumes before replacing the on-disk last-valid
 * manifest (D9). {@code parse} never touches disk — reading and replacing are separate acts.
 *
 * <p>Step 5 (bead j7e, Rev5.1 boundary): the preset-level layer on top — {@link #availablePresets()} and
 * {@link #shuffledQueue(ArtPreset, long)} — plus the {@link ArtPreset} catalog and its shared effective-preset
 * resolver in that sibling file. Both are pure functions of an already-parsed, already-validated manifest; neither
 * touches disk, the network, or any shared/mutable state.
 */
final class ArtManifest {
    /** A parsed candidate with more entries than this is invalid. */
    static final int MAX_MANIFEST_ENTRIES = 500;
    /** Byte cap on the manifest document; the fetch is capped at the same figure. */
    static final long MANIFEST_MAX_BYTES = 256L * 1024;

    private static final Pattern SHA1_HEX = Pattern.compile("[0-9a-f]{40}");

    /** One painting. {@code file} is the immutable content-addressed name; {@code sha1} the derivative's full digest. */
    static final class Entry {
        final long id;
        final String title;
        final String year;
        final List<String> categories;
        final String file;
        final String sha1;

        Entry(long id, String title, String year, List<String> categories, String file, String sha1) {
            this.id = id;
            this.title = title;
            this.year = year;
            this.categories = categories;
            this.file = file;
            this.sha1 = sha1;
        }
    }

    private final String baseUrl;
    private final List<Entry> entries;

    private ArtManifest(String baseUrl, List<Entry> entries) {
        this.baseUrl = baseUrl;
        this.entries = Collections.unmodifiableList(entries);
    }

    String baseUrl() { return baseUrl; }

    /** Never empty: {@link #parse(byte[])} rejects a manifest with zero valid entries. */
    List<Entry> entries() { return entries; }

    /**
     * Parses and structurally validates candidate bytes. ok(manifest) only when: the bytes are within
     * {@link #MANIFEST_MAX_BYTES}; the document is a JSON object; {@code base_url} equals {@link ArtHosts#ART_BASE_URL}
     * exactly; the {@code entries} array is present with at most {@link #MAX_MANIFEST_ENTRIES} elements; and at least
     * one entry is well-formed. Malformed entries are skipped at the boundary (an unreadable element, a non-positive
     * {@code id}, an unsafe {@code file}, or a {@code sha1} that is not 40 lowercase hex). err otherwise — the caller
     * keeps its last-valid manifest untouched.
     */
    static FetchResult<ArtManifest> parse(byte[] bytes) {
        if (bytes == null) return FetchResult.err("no manifest bytes");
        if (bytes.length > MANIFEST_MAX_BYTES) return FetchResult.err("manifest over cap: " + bytes.length);
        JSONObject root;
        try {
            root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException | NullPointerException e) {
            return FetchResult.err("not a JSON object");
        }
        String baseUrl = root.optString("base_url", "");
        if (!ArtHosts.ART_BASE_URL.equals(baseUrl)) {
            return FetchResult.err("base_url is not " + ArtHosts.ART_BASE_URL + ": '" + baseUrl + "'");
        }
        JSONArray array = root.optJSONArray("entries");
        if (array == null) return FetchResult.err("no entries array");
        if (array.length() > MAX_MANIFEST_ENTRIES) return FetchResult.err("too many entries: " + array.length());
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Entry entry = entryOf(array.optJSONObject(i));
            if (entry != null) entries.add(entry);
        }
        if (entries.isEmpty()) return FetchResult.err("no valid entries");
        return FetchResult.ok(new ArtManifest(baseUrl, entries));
    }

    /** True when {@code bytes} are a fully valid candidate (same checks as {@link #parse(byte[])}). */
    static boolean isValidCandidate(byte[] bytes) {
        return parse(bytes).isOk();
    }

    /**
     * The ordered presets (catalog declaration order) whose match count over {@link #entries()} reaches
     * {@link ArtPreset#MIN_PRESET_WORKS} (D6); {@link ArtPreset#ALL} is exempt from the threshold and included
     * whenever the pool is nonempty. An empty pool yields an empty list — the valid "stay on starfield, category
     * row disabled" state (a parsed instance never actually has an empty pool, since {@link #parse(byte[])} rejects
     * zero valid entries, but this stays defensive rather than relying on that invariant).
     */
    List<ArtPreset> availablePresets() {
        List<ArtPreset> available = new ArrayList<>();
        if (entries.isEmpty()) return available;
        for (ArtPreset preset : ArtPreset.values()) {
            if (preset == ArtPreset.ALL || preset.filter(entries).size() >= ArtPreset.MIN_PRESET_WORKS) {
                available.add(preset);
            }
        }
        return available;
    }

    /**
     * {@code preset}'s subset of {@link #entries()}, deterministically shuffled by {@code seed}; may be empty (a
     * valid "stay on starfield" state). Same seed + same filtered subset → same order every time.
     */
    List<Entry> shuffledQueue(ArtPreset preset, long seed) {
        List<Entry> queue = preset.filter(entries);
        Collections.shuffle(queue, new Random(seed));
        return queue;
    }

    /** One entry, or null when it is malformed (skipped at the boundary). */
    private static Entry entryOf(JSONObject o) {
        if (o == null) return null;
        long id = o.optLong("id", 0L);
        if (id <= 0) return null;
        String file = o.optString("file", "");
        if (!ArtHosts.isSafeFile(file)) return null;
        String sha1 = o.optString("sha1", "").toLowerCase(Locale.ROOT);
        if (!SHA1_HEX.matcher(sha1).matches()) return null;
        String title = o.optString("title", "");
        String year = o.optString("year", "");
        List<String> categories = new ArrayList<>();
        JSONArray raw = o.optJSONArray("categories");
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                String c = raw.optString(i, "");
                if (!c.isEmpty()) categories.add(c);
            }
        }
        return new Entry(id, title, year, Collections.unmodifiableList(categories), file, sha1);
    }
}
