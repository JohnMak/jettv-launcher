package com.johnmak.atvlauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * The only producer of {@link VerifiedApk}. A downloaded APK is accepted when its SHA-256 equals the published digest,
 * it is our package at exactly the announced version, that version is newer than the installed one, and it is signed
 * by the same certificates as the installed app.
 */
final class ApkVerifier {
    /** What the package manager says about an APK file or about the installed app. */
    static final class Facts {
        final String packageName;
        final String versionName;
        final long versionCode;
        /** SHA-256 hex digests of the signing certificates. */
        final Set<String> signers;

        Facts(String packageName, String versionName, long versionCode, Set<String> signers) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signers = signers;
        }
    }

    /** The package-manager boundary. */
    interface Inspector {
        /** Facts of an APK file; err if it cannot be parsed. */
        FetchResult<Facts> archive(File apk);

        /** Facts of the running app. */
        Facts installed();
    }

    private final Inspector inspector;

    ApkVerifier(Inspector inspector) {
        this.inspector = inspector;
    }

    FetchResult<VerifiedApk> verify(File apk, File shaFile, SemVer expected) {
        String digestText;
        try {
            digestText = new String(Files.readAllBytes(shaFile.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("cannot read checksum file: " + e);
        }
        FetchResult<String> published = ApkChecks.parseSha256(digestText);
        if (!published.isOk()) return fail(published.error());
        FetchResult<String> actual = sha256(apk);
        if (!actual.isOk()) return actual.propagate();
        if (!published.value().equals(actual.value())) return fail("sha256 mismatch");

        FetchResult<Facts> archive = inspector.archive(apk);
        if (!archive.isOk()) return fail("unreadable apk: " + archive.error());
        Facts candidate = archive.value();
        Facts installed = inspector.installed();
        if (!installed.packageName.equals(candidate.packageName)) return fail("package " + candidate.packageName);
        if (!expected.toString().equals(candidate.versionName)) return fail("versionName " + candidate.versionName);
        if (candidate.versionCode <= installed.versionCode) return fail("versionCode " + candidate.versionCode + " not newer");
        if (!ApkChecks.sameSigners(installed.signers, candidate.signers)) return fail("signing certificates differ");
        return FetchResult.ok(new VerifiedApk(apk, expected));
    }

    private static FetchResult<String> sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return FetchResult.ok(ApkChecks.hex(md.digest()));
        } catch (IOException e) {
            return fail("cannot read apk: " + e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JVM / Android
        }
    }

    private static <T> FetchResult<T> fail(String message) {
        return UpdateError.fail(UpdateError.VERIFY_FAILED, message);
    }
}
