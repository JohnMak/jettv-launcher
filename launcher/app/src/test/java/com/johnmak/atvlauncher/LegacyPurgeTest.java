package com.johnmak.atvlauncher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link LegacyPurge#deleteRecursive} is the pure-File part of the one-time cleanup (bead jm-works-jettv-launcher-63j
 * for the orphaned {@code art-cache/} directory); it needs no {@code Context}, so it is tested directly against a
 * real temp filesystem. This is deliberately separate from any steady-state "no images are written to disk" assertion
 * for the live Wallhaven source (a different concern, covered elsewhere).
 */
public class LegacyPurgeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void deletesNestedDirectoryManifestAndStrayPartFiles() throws IOException {
        File artCache = new File(tmp.getRoot(), "art-cache");
        File images = new File(artCache, "images");
        assertTrue(images.mkdirs());
        write(new File(artCache, "manifest.json"), "{}");
        write(new File(artCache, "manifest-123.part"), "x");
        write(new File(images, "a.jpg"), "a");
        write(new File(images, "b.jpg"), "b");
        write(new File(images, "c-456.part"), "c");

        LegacyPurge.deleteRecursive(artCache);

        assertFalse(artCache.exists());
    }

    @Test
    public void missingDirectoryIsANoOp() {
        File neverExisted = new File(tmp.getRoot(), "art-cache");
        LegacyPurge.deleteRecursive(neverExisted); // must not throw
        assertFalse(neverExisted.exists());
    }

    @Test
    public void isIdempotentOnARepeatRun() throws IOException {
        File artCache = new File(tmp.getRoot(), "art-cache");
        assertTrue(artCache.mkdirs());
        write(new File(artCache, "manifest.json"), "{}");

        LegacyPurge.deleteRecursive(artCache);
        assertFalse(artCache.exists());

        LegacyPurge.deleteRecursive(artCache); // repeat run, nothing left: still must not throw
        assertFalse(artCache.exists());
    }

    @Test
    public void deletingAPlainFileWorksToo() throws IOException {
        File file = new File(tmp.getRoot(), "stray.json");
        write(file, "{}");

        LegacyPurge.deleteRecursive(file);

        assertFalse(file.exists());
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes());
    }
}
