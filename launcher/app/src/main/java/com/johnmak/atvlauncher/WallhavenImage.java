package com.johnmak.atvlauncher;

/**
 * One Wallhaven wallpaper descriptor — the successor to the retired private-manifest entry, with no sha1 and no
 * manifest file-name (the private catalog's content-addressing is gone; see plan B3/B5). Immutable value.
 *
 * <ul>
 *   <li>{@link #id} — the Wallhaven wallpaper id (e.g. {@code "94x38z"}); used ONLY for per-seed no-repeat tracking.</li>
 *   <li>{@link #path} — the full-resolution image URL on {@code w.wallhaven.cc} ({@code data[].path}); already
 *       validated against {@link WallhavenHosts#check} at parse time.</li>
 *   <li>{@link #resolution} — the advertised resolution string (e.g. {@code "3840x2160"}); informational.</li>
 * </ul>
 */
final class WallhavenImage {
    final String id;
    final String path;
    final String resolution;

    WallhavenImage(String id, String path, String resolution) {
        this.id = id;
        this.path = path;
        this.resolution = resolution;
    }
}
