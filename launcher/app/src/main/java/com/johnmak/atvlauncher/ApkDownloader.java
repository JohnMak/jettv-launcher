package com.johnmak.atvlauncher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.IntConsumer;

/**
 * Downloads a release (APK + checksum file) into one cache directory. Redirects are followed by hand and only to
 * {@link UpdateHosts}; the body is capped. Blocking, worker thread only.
 */
final class ApkDownloader implements UpdateController.Downloader {
    static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    static final long MAX_SHA_BYTES = 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final File dir;
    private final String userAgent;

    ApkDownloader(File dir, String userAgent) {
        this.dir = dir;
        this.userAgent = userAgent;
    }

    /** Removes every file of earlier downloads. */
    @Override
    public void clear() {
        File[] old = dir.listFiles();
        if (old == null) return;
        for (File f : old) {
            if (!f.delete()) f.deleteOnExit();
        }
    }

    @Override
    public FetchResult<UpdateController.Download> download(Release release, IntConsumer progressPercent) {
        clear();
        if (!dir.isDirectory() && !dir.mkdirs()) return UpdateError.fail(UpdateError.DOWNLOAD_FAILED, "cannot create " + dir);
        File sha = new File(dir, Release.apkAssetName(release.version) + ".sha256");
        File apk = new File(dir, Release.apkAssetName(release.version));
        FetchResult<File> shaResult = fetch(release.sha256Url, sha, MAX_SHA_BYTES, p -> { });
        if (!shaResult.isOk()) return shaResult.propagate();
        FetchResult<File> apkResult = fetch(release.apkUrl, apk, MAX_APK_BYTES, progressPercent);
        if (!apkResult.isOk()) return apkResult.propagate();
        return FetchResult.ok(new UpdateController.Download(apk, sha));
    }

    private FetchResult<File> fetch(String url, File dest, long maxBytes, IntConsumer progress) {
        File part = new File(dest.getPath() + ".part");
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            FetchResult<String> allowed = UpdateHosts.check(current);
            if (!allowed.isOk()) return UpdateError.fail(UpdateError.NETWORK, allowed.error());
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(current).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestProperty("User-Agent", userAgent);
                int status = c.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    FetchResult<String> next = UpdateHosts.redirect(current, c.getHeaderField("Location"));
                    if (!next.isOk()) return UpdateError.fail(UpdateError.NETWORK, next.error());
                    current = next.value();
                    continue;
                }
                if (status != 200) return UpdateError.fail(UpdateError.NETWORK, "HTTP " + status + " for " + current);
                long length = c.getContentLengthLong();
                if (length > maxBytes) return UpdateError.fail(UpdateError.DOWNLOAD_FAILED, "too large: " + length);
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part.toPath())) {
                    FetchResult<Long> copied = copyCapped(in, out, maxBytes, length, progress);
                    if (!copied.isOk()) return copied.propagate();
                    if (length >= 0 && copied.value() != length) {
                        return UpdateError.fail(UpdateError.DOWNLOAD_FAILED, "short body " + copied.value() + "/" + length);
                    }
                }
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return FetchResult.ok(dest);
            } catch (IOException | RuntimeException e) { // I/O boundary
                return UpdateError.fail(UpdateError.NETWORK, String.valueOf(e));
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return UpdateError.fail(UpdateError.NETWORK, "too many redirects");
    }

    /**
     * Copies at most {@code maxBytes}; reports each new whole percent of {@code expectedLength} (when known, else -1 once).
     * ok(bytes copied), or DOWNLOAD_FAILED when the stream is longer than the cap.
     */
    static FetchResult<Long> copyCapped(InputStream in, OutputStream out, long maxBytes, long expectedLength,
                                        IntConsumer progress) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int lastPercent = Integer.MIN_VALUE;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) return UpdateError.fail(UpdateError.DOWNLOAD_FAILED, "exceeds " + maxBytes + " bytes");
            out.write(buf, 0, n);
            int percent = expectedLength > 0 ? (int) (total * 100 / expectedLength) : UpdateState.NO_PROGRESS;
            if (percent != lastPercent) {
                lastPercent = percent;
                progress.accept(percent);
            }
        }
        return FetchResult.ok(total);
    }
}
