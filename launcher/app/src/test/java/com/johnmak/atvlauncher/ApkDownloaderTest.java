package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ApkDownloaderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void copyReportsEachNewPercentOnce() throws IOException {
        byte[] data = new byte[200_000];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> seen = new ArrayList<>();
        FetchResult<Long> r = ApkDownloader.copyCapped(new ByteArrayInputStream(data), out, 1_000_000, data.length, seen::add);
        assertEquals(200_000L, (long) r.value());
        assertEquals(200_000, out.size());
        assertEquals(100, (int) seen.get(seen.size() - 1));
        assertEquals(seen.size(), new java.util.HashSet<>(seen).size());
    }

    @Test public void copyWithoutKnownLengthReportsNoProgress() throws IOException {
        List<Integer> seen = new ArrayList<>();
        ApkDownloader.copyCapped(new ByteArrayInputStream(new byte[10]), new ByteArrayOutputStream(), 100, -1, seen::add);
        assertEquals(Arrays.asList(UpdateState.NO_PROGRESS), seen);
    }

    @Test public void copyStopsAtTheCap() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FetchResult<Long> r = ApkDownloader.copyCapped(new ByteArrayInputStream(new byte[100_000]), out, 70_000, -1, p -> { });
        assertFalse(r.isOk());
        assertEquals(UpdateError.DOWNLOAD_FAILED, UpdateError.of(r));
        assertTrue(out.size() <= 70_000);
    }

    @Test public void clearRemovesOldFiles() throws IOException {
        File dir = tmp.newFolder("update");
        assertTrue(new File(dir, "old.apk").createNewFile());
        assertTrue(new File(dir, "old.apk.part").createNewFile());
        new ApkDownloader(dir, "ua").clear();
        assertEquals(0, dir.listFiles().length);
        new ApkDownloader(new File(dir, "missing"), "ua").clear(); // no directory yet: nothing to do
    }
}
