package com.alexmak.atvlauncher;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Poster bitmaps: memory LRU (~8 MB), downsampled decode, and a disk cache (~30 MB) for downloads.
 * {@link #load} blocks and belongs on a worker thread; a poster that cannot be loaded yields null
 * and is not retried until {@link #forgetFailures()}.
 */
final class PosterCache {
    private static final String TAG = "AtvPoster";
    private static final int MEMORY_BYTES = 8 * 1024 * 1024;
    private static final long DISK_BYTES = 30L * 1024 * 1024;
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final int TIMEOUT_MS = 5000;

    private interface Source {
        InputStream open() throws IOException;
    }

    private final ContentResolver resolver;
    private final File dir;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(MEMORY_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<String> failed = new HashSet<>();

    PosterCache(ContentResolver resolver, File cacheDir) {
        this.resolver = resolver;
        this.dir = new File(cacheDir, "posters");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "cannot create " + dir);
    }

    Bitmap peek(String uri) {
        return memory.get(uri);
    }

    /** Drops every in-memory poster; they reload from the disk cache. */
    void evictAll() {
        memory.evictAll();
    }

    synchronized void forgetFailures() {
        failed.clear();
    }

    /** Returns the poster scaled to cover reqW x reqH exactly (aspect kept, never upscaled), or null when it cannot be loaded. */
    Bitmap load(String uri, int reqW, int reqH) {
        Bitmap hit = memory.get(uri);
        if (hit != null) return hit;
        synchronized (this) {
            if (failed.contains(uri)) return null;
        }
        Bitmap bmp = decodeUri(uri, reqW, reqH);
        if (bmp == null) {
            synchronized (this) {
                failed.add(uri);
            }
            return null;
        }
        memory.put(uri, bmp);
        return bmp;
    }

    /**
     * Poster for the page backdrop: the cached bitmap if present, else decoded from the disk cache/network WITHOUT
     * entering the memory LRU (the LRU holds card-sized bitmaps and may have been trimmed). Worker thread only.
     */
    Bitmap loadForBackdrop(String uri, int reqW, int reqH) {
        Bitmap hit = memory.get(uri);
        if (hit != null) return hit;
        synchronized (this) {
            if (failed.contains(uri)) return null;
        }
        return decodeUri(uri, reqW, reqH);
    }

    private Bitmap decodeUri(String uri, int reqW, int reqH) {
        Bitmap bmp = null;
        try {
            Uri parsed = Uri.parse(uri);
            String scheme = parsed.getScheme();
            if ("content".equals(scheme)) {
                bmp = decode(() -> resolver.openInputStream(parsed), reqW, reqH);
            } else if ("http".equals(scheme) || "https".equals(scheme)) {
                File file = fetch(uri);
                bmp = decode(() -> new FileInputStream(file), reqW, reqH);
            } else {
                Log.w(TAG, "unsupported poster scheme: " + uri);
            }
        } catch (IOException | RuntimeException ex) {
            Log.w(TAG, "poster failed: " + uri + " (" + ex + ")");
        }
        return bmp;
    }

    private static Bitmap decode(Source source, int reqW, int reqH) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = source.open()) {
            if (in == null) throw new IOException("no stream");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not an image");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = PosterSampling.sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded;
        try (InputStream in = source.open()) {
            if (in == null) throw new IOException("no stream");
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) return null;
        int[] size = PosterSampling.coverSize(decoded.getWidth(), decoded.getHeight(), reqW, reqH);
        if (size[0] == decoded.getWidth() && size[1] == decoded.getHeight()) return decoded;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, size[0], size[1], true);
        decoded.recycle();
        return scaled;
    }

    /** Downloads into the disk cache unless already there; returns the cached file. */
    private File fetch(String uri) throws IOException {
        File file = new File(dir, sha1(uri));
        if (file.isFile()) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        File tmp = File.createTempFile("dl_", ".tmp", dir);
        HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("poster too large");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(file)) throw new IOException("cannot store " + file);
        } finally {
            conn.disconnect();
            if (tmp.exists() && !tmp.delete()) Log.w(TAG, "cannot delete " + tmp);
        }
        trimDisk();
        return file;
    }

    private synchronized void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= DISK_BYTES) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is part of every Android runtime", e);
        }
    }
}
