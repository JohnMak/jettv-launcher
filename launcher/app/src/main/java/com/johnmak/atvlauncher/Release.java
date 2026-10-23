package com.johnmak.atvlauncher;

/** An acceptable published release: its version and the two validated download URLs. */
final class Release {
    final SemVer version;
    final String apkUrl;
    final String sha256Url;

    Release(SemVer version, String apkUrl, String sha256Url) {
        this.version = version;
        this.apkUrl = apkUrl;
        this.sha256Url = sha256Url;
    }

    /** The asset name of the APK; the checksum asset is this plus {@code .sha256}. */
    static String apkAssetName(SemVer version) {
        return "jettv-launcher-" + version + ".apk";
    }
}
