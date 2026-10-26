package com.johnmak.atvlauncher;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The on-device art cache under {@code getFilesDir()/art-cache/}: a last-valid {@code manifest.json} (outside the LRU
 * budget, bounded by its own cap) and {@code images/} (the LRU domain, size-capped, integrity-checked, atomic writes).
 *
 * <p>The size/eviction/reserve <em>decisions</em> are pure static functions ({@link #decide}, {@link #evictionPlan},
 * {@link #evictFor}) unit-tested without a filesystem; the instance ties them to real files. {@code CACHE_MAX} covers
 * {@code images/} including {@code .part} temporaries; the manifest is excluded.
 *
 * <p>Concurrency contract (worker threads, up to two in flight): every write streams into its OWN unique {@code .part}
 * temp (no two writers ever share a temp) and is installed by an atomic rename. A single {@code diskLock} serializes
 * all space accounting, eviction, pin changes and installs, so eviction never races an install and a newly pinned file
 * can never be evicted. Pins are reference-counted — a file stays protected until every holder releases it. Free-space
 * reservations are accounted under the lock (each admitted-but-unfinished write reserves one worst-case image), so two
 * writers can never be admitted against the same space.
 */
final class ArtCache {
    static final long CACHE_MAX = 60L * 1024 * 1024;
    static final long IMAGE_MAX_BYTES = 4L * 1024 * 1024;
    static final long RESERVE_FREE_BYTES = 20L * 1024 * 1024;
    private static final String PART_SUFFIX = ".part";

    /** What to do before writing an image, given free disk and what eviction could reclaim. */
    enum Reserve { WRITE, EVICT_THEN_WRITE, UNAVAILABLE }

    /** An image file's name, last-access time and size — the pure input to {@link #evictionPlan}. */
    static final class Stat {
        final String name;
        final long lastAccess;
        final long size;

        Stat(String name, long lastAccess, long size) {
            this.name = name;
            this.lastAccess = lastAccess;
            this.size = size;
        }
    }

    private final File root;
    private final File manifestFile;
    private final File imagesDir;
    private final LongSupplier usableSpace;
    private final Object diskLock = new Object();
    /** Reference-counted pins (guarded by {@link #diskLock}): a file is protected while its count is > 0. */
    private final Map<String, Integer> pins = new HashMap<>();
    /** Admitted-but-unfinished image writes (guarded by {@link #diskLock}); each reserves one worst-case image. */
    private int inFlightReservations;

    ArtCache(File cacheDir) {
        this(cacheDir, null);
    }

    /** {@code usableSpace} overrides the free-disk probe (tests inject a controlled value); null = the real probe. */
    ArtCache(File cacheDir, LongSupplier usableSpace) {
        this.root = new File(cacheDir, "art-cache");
        this.manifestFile = new File(root, "manifest.json");
        this.imagesDir = new File(root, "images");
        this.usableSpace = usableSpace != null ? usableSpace : this::probeUsableSpace;
    }

    // ---- pure decisions (no filesystem) -------------------------------------------------------

    /**
     * Whether {@code need} bytes can be written while keeping {@code reserve} bytes free, possibly after eviction.
     * WRITE when free space already suffices; EVICT_THEN_WRITE when evicting reclaims enough; UNAVAILABLE otherwise.
     */
    static Reserve decide(long usableSpace, long evictableBytes, long need, long reserve) {
        if (usableSpace >= need + reserve) return Reserve.WRITE;
        if (usableSpace + evictableBytes >= need + reserve) return Reserve.EVICT_THEN_WRITE;
        return Reserve.UNAVAILABLE;
    }

    /**
     * Names to delete, oldest-access first, to bring the total under {@code cap}. Pinned entries and {@code .part}
     * temporaries are never chosen (temporaries belong to in-flight fetches). May be shorter than needed when only
     * pinned/temporary files remain — eviction is best-effort and never deletes a file in use.
     */
    static List<String> evictionPlan(List<Stat> stats, Set<String> pinned, long cap) {
        long total = 0;
        for (Stat s : stats) total += s.size;
        if (total <= cap) return Collections.emptyList();
        List<String> victims = new ArrayList<>();
        for (Stat s : eligibleOldestFirst(stats, pinned)) {
            if (total <= cap) break;
            victims.add(s.name);
            total -= s.size;
        }
        return victims;
    }

    /**
     * Names to delete, oldest-access first, to reclaim at least {@code deficitBytes} of disk. Used for disk-pressure
     * reservation (distinct from the 60 MiB cache cap). Pinned and {@code .part} files are never chosen.
     */
    static List<String> evictFor(List<Stat> stats, Set<String> pinned, long deficitBytes) {
        if (deficitBytes <= 0) return Collections.emptyList();
        List<String> victims = new ArrayList<>();
        long freed = 0;
        for (Stat s : eligibleOldestFirst(stats, pinned)) {
            if (freed >= deficitBytes) break;
            victims.add(s.name);
            freed += s.size;
        }
        return victims;
    }

    private static List<Stat> eligibleOldestFirst(List<Stat> stats, Set<String> pinned) {
        List<Stat> eligible = new ArrayList<>();
        for (Stat s : stats) {
            if (s.name.endsWith(PART_SUFFIX)) continue;
            if (pinned.contains(s.name)) continue;
            eligible.add(s);
        }
        eligible.sort(Comparator.comparingLong(s -> s.lastAccess));
        return eligible;
    }

    /** SHA-1 of {@code data} as lowercase hex. */
    static String sha1Hex(byte[] data) {
        try {
            return ApkChecks.hex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is part of every Android runtime", e);
        }
    }

    // ---- pins (reference-counted, guarded by diskLock) ----------------------------------------

    /** Marks {@code file} as in use so eviction never deletes it; each call must be paired with {@link #unpin}. */
    void pin(String file) {
        synchronized (diskLock) {
            pins.merge(file, 1, Integer::sum);
        }
    }

    /** Releases one hold on {@code file}; protection ends only when the last holder releases. Underflow is a no-op. */
    void unpin(String file) {
        synchronized (diskLock) {
            pins.computeIfPresent(file, (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    /** Whether {@code file} currently has at least one outstanding pin (eviction-safety query). */
    boolean isPinned(String file) {
        synchronized (diskLock) {
            return pins.containsKey(file);
        }
    }

    // ---- filesystem (worker thread) -----------------------------------------------------------

    /** A fresh unique {@code .part} temp for {@code file}; the fetch streams here before integrity verification. */
    FetchResult<File> newPart(String file) {
        if (!ensureDir(imagesDir)) return FetchResult.err("cannot create images dir");
        try {
            return FetchResult.ok(Files.createTempFile(imagesDir.toPath(), file + "-", PART_SUFFIX).toFile());
        } catch (IOException | RuntimeException e) {
            return FetchResult.err("cannot create temp for " + file + ": " + e);
        }
    }

    /** Deletes stray {@code *.part} left by a cancelled fetch or process death (images + manifest temps). */
    void sweepParts() {
        sweepPartsIn(imagesDir);
        sweepPartsIn(root);
    }

    private static void sweepPartsIn(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(PART_SUFFIX) && !f.delete()) f.deleteOnExit();
        }
    }

    /** The on-disk last-valid manifest bytes, or err when none has ever been stored. */
    FetchResult<byte[]> readManifest() {
        if (!manifestFile.isFile()) return FetchResult.err("no manifest");
        try {
            return FetchResult.ok(Files.readAllBytes(manifestFile.toPath()));
        } catch (IOException e) {
            return FetchResult.err("cannot read manifest: " + e);
        }
    }

    /**
     * Replaces the last-valid manifest with {@code validated} bytes via a UNIQUE temp plus an atomic rename. The caller
     * MUST have validated the bytes first ({@link ArtManifest#isValidCandidate}); this only persists them. On any
     * failure the existing last-valid manifest is left untouched (it is never deleted before the atomic install).
     */
    FetchResult<File> writeManifestAtomic(byte[] validated) {
        if (!ensureDir(root)) return FetchResult.err("cannot create cache dir");
        File part;
        try {
            part = Files.createTempFile(root.toPath(), "manifest-", PART_SUFFIX).toFile();
            Files.write(part.toPath(), validated);
        } catch (IOException e) {
            return FetchResult.err("cannot write manifest temp: " + e);
        }
        synchronized (diskLock) {
            FetchResult<File> installed = atomicReplace(part, manifestFile);
            if (!installed.isOk()) part.delete();
            return installed;
        }
    }

    /**
     * The verified cache file for {@code file} when present and its content matches {@code sha1} (last-access is
     * touched); null on a miss. A file whose bytes no longer match is corrupt: it is deleted and treated as a miss.
     */
    File hit(String file, String sha1) {
        synchronized (diskLock) { // same lock as commit(): a corrupt-delete can never race a concurrent install
            File f = new File(imagesDir, file);
            if (!f.isFile()) return null;
            if (!matches(f, sha1)) {
                f.delete();
                return null;
            }
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
    }

    /**
     * Admits one worst-case image write, accounting for other in-flight writes and evicting for the disk DEFICIT if
     * needed; false = unavailable (no write admitted). A successful reservation MUST be released with
     * {@link #releaseReservation()} when the write finishes (success or failure).
     */
    boolean reserveForImage() {
        synchronized (diskLock) {
            long want = (long) (inFlightReservations + 1) * IMAGE_MAX_BYTES + RESERVE_FREE_BYTES;
            long usable = usableSpace.getAsLong();
            Reserve d = decide(usable, evictableBytesLocked(), want - RESERVE_FREE_BYTES, RESERVE_FREE_BYTES);
            if (d == Reserve.UNAVAILABLE) return false;
            if (d == Reserve.EVICT_THEN_WRITE) {
                for (String victim : evictFor(imageStats(), pinnedNamesLocked(), want - usable)) {
                    new File(imagesDir, victim).delete();
                }
                usable = usableSpace.getAsLong();
                if (usable < want) return false;
            }
            inFlightReservations++;
            return true;
        }
    }

    /** Releases one reservation taken by {@link #reserveForImage()}. */
    void releaseReservation() {
        synchronized (diskLock) {
            if (inFlightReservations > 0) inFlightReservations--;
        }
    }

    /**
     * Verifies the fetched {@code part} against {@code sha1}, then installs it as {@code images/<file>} via an atomic
     * rename, evicting to stay under {@link #CACHE_MAX}. A mismatch (corrupt download) deletes the part and errs. The
     * sha1 verification runs outside the lock; only the install + eviction are serialized.
     */
    FetchResult<File> commit(File part, String file, String sha1) {
        if (!matches(part, sha1)) {
            part.delete();
            return FetchResult.err("sha1 mismatch for " + file);
        }
        File dest = new File(imagesDir, file);
        synchronized (diskLock) {
            FetchResult<File> installed = atomicReplace(part, dest);
            if (!installed.isOk()) {
                part.delete();
                return installed;
            }
            for (String victim : evictionPlan(imageStats(), pinnedNamesLocked(), CACHE_MAX)) {
                new File(imagesDir, victim).delete();
            }
            return FetchResult.ok(dest);
        }
    }

    boolean ensureImagesDir() { return ensureDir(imagesDir); }

    // ---- internals ----------------------------------------------------------------------------

    /**
     * Installs {@code src}→{@code dst} with a single {@link StandardCopyOption#ATOMIC_MOVE} (all-or-nothing). The ONLY
     * operation that touches {@code dst} is this atomic move, so a failure can never destroy the existing (last-valid)
     * {@code dst}. ATOMIC_MOVE is unsupported ONLY across filesystems; every temp here is created in {@code dst}'s own
     * directory, so in practice it is always supported — if it ever is not, we fail closed rather than do a destructive
     * non-atomic replace that could delete the old file before the new one is in place. Package-private for testing.
     */
    static FetchResult<File> atomicReplace(File src, File dst) {
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return FetchResult.ok(dst);
        } catch (AtomicMoveNotSupportedException unsupported) {
            return FetchResult.err("atomic install unsupported for " + dst.getName() + "; last-valid retained");
        } catch (IOException e) {
            return FetchResult.err("cannot install " + dst.getName() + ": " + e); // dst (old) left intact
        }
    }

    private long evictableBytesLocked() {
        long sum = 0;
        Set<String> pinned = pinnedNamesLocked();
        for (Stat s : imageStats()) {
            if (!s.name.endsWith(PART_SUFFIX) && !pinned.contains(s.name)) sum += s.size;
        }
        return sum;
    }

    private List<Stat> imageStats() {
        File[] files = imagesDir.listFiles();
        List<Stat> stats = new ArrayList<>();
        if (files == null) return stats;
        for (File f : files) {
            if (f.isFile()) stats.add(new Stat(f.getName(), f.lastModified(), f.length()));
        }
        return stats;
    }

    /** Current pin set; caller already holds {@link #diskLock}. */
    private Set<String> pinnedNamesLocked() {
        return new HashSet<>(pins.keySet());
    }

    private long probeUsableSpace() {
        File f = imagesDir;
        while (f != null && !f.exists()) f = f.getParentFile();
        return f == null ? 0L : f.getUsableSpace();
    }

    private static boolean matches(File f, String sha1) {
        try {
            return sha1Hex(Files.readAllBytes(f.toPath())).equals(sha1);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean ensureDir(File dir) {
        return dir != null && (dir.isDirectory() || dir.mkdirs());
    }
}
