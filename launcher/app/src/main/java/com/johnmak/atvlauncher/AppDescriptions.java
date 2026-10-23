package com.johnmak.atvlauncher;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** One-sentence plain-language descriptions of known packages, parsed strictly from assets/app_descriptions.tsv. */
final class AppDescriptions {
    static final String ASSET = "app_descriptions.tsv";
    static final String SYSTEM_FALLBACK = "Системный компонент производителя";
    static final String USER_FALLBACK = "Установленное приложение";

    private final Map<String, String> byPackage;

    private AppDescriptions(Map<String, String> byPackage) { this.byPackage = byPackage; }

    /** Format: {@code package<TAB>sentence} per line; blank lines and lines starting with '#' are ignored. */
    static FetchResult<AppDescriptions> parse(String text) {
        if (text == null) return FetchResult.err("descriptions text is null");
        Map<String, String> map = new HashMap<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int n = i + 1;
            String line = lines[i];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) return FetchResult.err("line " + n + ": missing tab");
            String pkg = line.substring(0, tab);
            FetchResult<PackageName> name = PackageName.parse(pkg);
            if (!name.isOk()) return FetchResult.err("line " + n + ": " + name.error());
            String sentence = line.substring(tab + 1).trim();
            if (sentence.isEmpty()) return FetchResult.err("line " + n + ": empty description for " + pkg);
            if (map.put(pkg, sentence) != null) return FetchResult.err("line " + n + ": duplicate package " + pkg);
        }
        return FetchResult.ok(new AppDescriptions(Collections.unmodifiableMap(map)));
    }

    /** Loads and parses the bundled asset. */
    static FetchResult<AppDescriptions> load(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return FetchResult.err("cannot read " + ASSET + ": " + e.getMessage());
        }
    }

    int size() { return byPackage.size(); }

    String describe(String pkg, boolean system) {
        String d = byPackage.get(pkg);
        if (d != null) return d;
        return system ? SYSTEM_FALLBACK : USER_FALLBACK;
    }
}
