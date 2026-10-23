package com.johnmak.atvlauncher;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure helpers of {@link ApkVerifier}: the checksum file format and the signer comparison. */
final class ApkChecks {
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private ApkChecks() {}

    /** The digest of a `sha256sum`-style file: first whitespace-separated token of the first line, 64 hex chars. */
    static FetchResult<String> parseSha256(String fileText) {
        if (fileText == null) return FetchResult.err("checksum file is null");
        String text = fileText.trim();
        if (text.isEmpty()) return FetchResult.err("checksum file is empty");
        String firstLine = text.split("\\R", 2)[0].trim();
        String token = firstLine.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!SHA256_HEX.matcher(token).matches()) return FetchResult.err("not a sha256 digest: '" + token + "'");
        return FetchResult.ok(token);
    }

    /** Hex of a byte array, lowercase. */
    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /** Same non-empty set of signing certificates (as SHA-256 hex digests). */
    static boolean sameSigners(Set<String> installed, Set<String> candidate) {
        return !installed.isEmpty() && installed.equals(candidate);
    }
}
