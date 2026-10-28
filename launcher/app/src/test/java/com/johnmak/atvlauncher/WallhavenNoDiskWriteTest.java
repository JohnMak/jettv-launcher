package com.johnmak.atvlauncher;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * E2E architectural guard for the rework's hard memory contract: the Wallhaven image slideshow decodes IN MEMORY with
 * NO disk cache — ZERO image disk writes (plan B1/B4; the RAM-only successor to the retired, disk-caching
 * {@code ArtSource}/{@code ArtCache}). This is the STEADY-STATE counterpart to {@link LegacyPurgeTest} (which covers
 * the ONE-TIME deletion of the orphaned {@code art-cache/} directory) — the two concerns are deliberately tested apart,
 * per the bead: one-time cleanup vs. the never-writes-again invariant.
 *
 * <p>Rather than assert the ABSENCE of an event (un-observable), this inspects the compiled bytecode of every class on
 * the steady-state image path and proves none of them even REFERENCES a disk-WRITE / file-handle API — so no future
 * edit can silently reintroduce an on-disk image cache without this test going red.
 *
 * <p><b>Bounded scope (documented, per the project's "last-resort text detection" doctrine — catch the natural forms,
 * document the residual gaps, do not chase infinitely).</b> Discovery covers the enumerated {@link #PIPELINE} classes
 * plus every nested AND anonymous class reachable from them (recursively). It does NOT follow a write DELEGATED to a
 * brand-new helper class that is not itself on the pipeline — such a helper MUST be added to {@link #PIPELINE}. Token
 * matching targets the common write/handle APIs; read-only stream types used for the NETWORK body
 * ({@code InputStream}, {@code IOException}) are deliberately excluded (they are not disk). {@link LegacyPurge} is
 * pointedly EXCLUDED: it is the cleanup, not the pipeline, and legitimately opens files to delete the orphaned cache.
 */
public class WallhavenNoDiskWriteTest {

    /** The steady-state image pipeline — fetch → in-memory bytes → decode → render. NONE may touch the filesystem. */
    private static final Class<?>[] PIPELINE = {
            WallhavenSource.class,   // the fetch boundary (successor to the disk-caching ArtSource)
            WallhavenSearch.class,   // the page parser
            WallhavenPager.class,    // the streaming window
            WallhavenImage.class,    // one descriptor
            WallhavenHosts.class,    // the allowlist
            DecodeBudget.class,      // the in-memory decode-byte budget
            KenBurns.class,          // the zoom math
            ArtPlayback.class,       // the phase/timing engine
            WallhavenCategory.class, // the category catalog
            ArtScreensaverView.class // the view + the pure Slideshow coordinator (+ its nested types)
    };

    /**
     * Disk-WRITE / file-handle reference tokens that must NOT appear in the constant pool of any pipeline class: the
     * file output/handle classes, the Android {@code Context}/{@code ContentResolver} file-write + dir accessors, and
     * {@code java.nio.file}. Targeted at WRITES specifically — the broad {@code java/io/File} is intentionally NOT used
     * (it would false-positive on read-only references such as {@code FileNotFoundException}).
     */
    private static final String[] DISK_TOKENS = {
            "FileOutputStream",
            "FileWriter",
            "RandomAccessFile",
            "FileChannel",
            "openFileOutput",         // Context.openFileOutput
            "openOutputStream",       // ContentResolver.openOutputStream
            "getFilesDir",
            "getCacheDir",
            "getExternalFilesDir",
            "getExternalCacheDir",
            "getDataDir",
            "java/nio/file/"          // Files.write / Path / newOutputStream, …
    };

    @Test public void noPipelineClassReferencesAnyDiskWriteApi() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> scanned = allClassResources(PIPELINE);
        for (String resource : scanned) {
            String constPool = new String(classBytes(resource), StandardCharsets.ISO_8859_1);
            for (String token : DISK_TOKENS) {
                if (constPool.contains(token)) offenders.add(resource + " references a disk API: " + token);
            }
        }
        // Discovery must have reached beyond the top-level roots (nested + anonymous classes), or it proves nothing.
        assertTrue("expected to have scanned the pipeline + its nested/anonymous classes, got " + scanned.size()
                + ": " + scanned, scanned.size() > PIPELINE.length);
        assertTrue("the steady-state Wallhaven image pipeline must perform ZERO disk writes, but: " + offenders,
                offenders.isEmpty());
    }

    /** The guard has teeth: {@link LegacyPurge} (the EXCLUDED one-time cleanup) genuinely DOES touch the filesystem, so
     * a token hit is real — proving the scan would catch a reintroduced on-disk image cache, not silently pass. */
    @Test public void theGuardWouldCatchADiskReference() throws IOException {
        String legacyPurge = new String(classBytes(resourceName(LegacyPurge.class)), StandardCharsets.ISO_8859_1);
        List<String> hits = new ArrayList<>();
        for (String token : DISK_TOKENS) {
            if (legacyPurge.contains(token)) hits.add(token);
        }
        assertTrue("LegacyPurge (the one-time cache deletion) must itself trip a disk token — proving the token set has"
                + " teeth and the pipeline's clean result is a true negative, not a dead scan; hits=" + hits,
                !hits.isEmpty());
    }

    // ---- bytecode discovery -------------------------------------------------------------------

    /** The class-file resource names for {@code roots}, every nested (named) class (recursively), and every anonymous
     * class reachable from any of them (recursively probed via {@code $N} suffixes). */
    private static Set<String> allClassResources(Class<?>[] roots) {
        Set<String> out = new LinkedHashSet<>();
        // Phase 1: named classes — roots + their declared (nested) classes, recursively.
        Deque<Class<?>> classes = new ArrayDeque<>();
        for (Class<?> c : roots) classes.add(c);
        List<String> namedBases = new ArrayList<>();
        while (!classes.isEmpty()) {
            Class<?> c = classes.poll();
            String base = c.getName().replace('.', '/');
            if (out.add(base + ".class")) namedBases.add(base);
            for (Class<?> nested : c.getDeclaredClasses()) classes.add(nested);
        }
        // Phase 2: anonymous classes — probe $1..$30 under every discovered base, recursively (a $N may itself nest).
        Deque<String> bases = new ArrayDeque<>(namedBases);
        while (!bases.isEmpty()) {
            String base = bases.poll();
            for (int n = 1; n <= 30; n++) {
                String anon = base + "$" + n;
                if (resourceExists(anon + ".class") && out.add(anon + ".class")) bases.add(anon);
            }
        }
        return out;
    }

    private static String resourceName(Class<?> c) {
        return c.getName().replace('.', '/') + ".class";
    }

    private static boolean resourceExists(String resource) {
        try (InputStream in = WallhavenNoDiskWriteTest.class.getClassLoader().getResourceAsStream(resource)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] classBytes(String resource) throws IOException {
        try (InputStream in = WallhavenNoDiskWriteTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull("class resource on the test classpath: " + resource, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
