package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ApkVerifierTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final Set<String> KEY = Collections.singleton("cert-a");
    private static final SemVer V = SemVer.parse("1.2.3").value();

    private File apk;
    private File sha;
    private ApkVerifier.Facts installed;
    private FetchResult<ApkVerifier.Facts> archive;
    private final ApkVerifier verifier = new ApkVerifier(new ApkVerifier.Inspector() {
        @Override public FetchResult<ApkVerifier.Facts> archive(File f) { return archive; }
        @Override public ApkVerifier.Facts installed() { return installed; }
    });

    static String sha256(byte[] data) throws Exception {
        return ApkChecks.hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void writeSha(String digest) throws IOException {
        Files.write(sha.toPath(), (digest + "  jettv-launcher-1.2.3.apk\n").getBytes(StandardCharsets.UTF_8));
    }

    @Before public void setUp() throws Exception {
        apk = tmp.newFile("a.apk");
        sha = tmp.newFile("a.apk.sha256");
        Files.write(apk.toPath(), "apk-bytes".getBytes(StandardCharsets.UTF_8));
        writeSha(sha256("apk-bytes".getBytes(StandardCharsets.UTF_8)));
        installed = new ApkVerifier.Facts("com.johnmak.atvlauncher", "1.2.2", 1_002_002, KEY);
        archive = FetchResult.ok(new ApkVerifier.Facts("com.johnmak.atvlauncher", "1.2.3", 1_002_003, KEY));
    }

    private void assertVerifyFailed() {
        FetchResult<VerifiedApk> r = verifier.verify(apk, sha, V);
        assertFalse(r.isOk());
        assertEquals(UpdateError.VERIFY_FAILED, UpdateError.of(r));
    }

    @Test public void acceptsMatchingNewerSignedApk() {
        FetchResult<VerifiedApk> r = verifier.verify(apk, sha, V);
        assertTrue(r.isOk() ? "" : r.error(), r.isOk());
        assertSame(apk, r.value().file());
        assertEquals(V, r.value().version());
    }

    @Test public void rejectsDigestMismatch() throws Exception {
        writeSha(sha256("other".getBytes(StandardCharsets.UTF_8)));
        assertVerifyFailed();
    }

    @Test public void rejectsUnparsableChecksumFile() throws Exception {
        Files.write(sha.toPath(), "oops".getBytes(StandardCharsets.UTF_8));
        assertVerifyFailed();
    }

    @Test public void rejectsForeignPackage() {
        archive = FetchResult.ok(new ApkVerifier.Facts("evil.pkg", "1.2.3", 1_002_003, KEY));
        assertVerifyFailed();
    }

    @Test public void rejectsVersionNameNotAnnounced() {
        archive = FetchResult.ok(new ApkVerifier.Facts("com.johnmak.atvlauncher", "1.2.4", 1_002_004, KEY));
        assertVerifyFailed();
    }

    @Test public void rejectsNotNewerVersionCode() {
        installed = new ApkVerifier.Facts("com.johnmak.atvlauncher", "1.2.3", 1_002_003, KEY);
        assertVerifyFailed();
    }

    @Test public void rejectsDifferentSigner() {
        archive = FetchResult.ok(new ApkVerifier.Facts("com.johnmak.atvlauncher", "1.2.3", 1_002_003,
                Collections.singleton("cert-b")));
        assertVerifyFailed();
    }

    @Test public void rejectsUnreadableArchive() {
        archive = FetchResult.err("bad");
        assertVerifyFailed();
    }
}
