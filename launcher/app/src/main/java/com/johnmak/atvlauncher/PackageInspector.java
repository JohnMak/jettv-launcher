package com.johnmak.atvlauncher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** {@link ApkVerifier.Inspector} over {@link PackageManager}. */
final class PackageInspector implements ApkVerifier.Inspector {
    private final PackageManager pm;
    private final String ownPackage;

    PackageInspector(Context context) {
        this.pm = context.getPackageManager();
        this.ownPackage = context.getPackageName();
    }

    @Override
    public FetchResult<ApkVerifier.Facts> archive(File apk) {
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null) return FetchResult.err("not an installable apk");
        return FetchResult.ok(facts(info));
    }

    @Override
    public ApkVerifier.Facts installed() {
        try {
            return facts(pm.getPackageInfo(ownPackage, PackageManager.GET_SIGNING_CERTIFICATES));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("own package missing", e);
        }
    }

    private static ApkVerifier.Facts facts(PackageInfo info) {
        Set<String> signers = new HashSet<>();
        SigningInfo signing = info.signingInfo;
        if (signing != null) {
            Signature[] certs = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
            if (certs != null) for (Signature s : certs) signers.add(ApkChecks.hex(sha256(s.toByteArray())));
        }
        return new ApkVerifier.Facts(info.packageName, info.versionName, info.getLongVersionCode(), signers);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
