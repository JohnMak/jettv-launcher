package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A device cleanup profile parsed from tools/debloat/*.txt (bundled as an asset). */
final class CleanupProfile {
    enum Action {
        DISABLE, REMOVE, RESET;

        static Action fromWord(String word) {
            switch (word) {
                case "disable": return DISABLE;
                case "remove": return REMOVE;
                case "reset": return RESET;
                default: return null;
            }
        }

        String word() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    static final class Entry {
        final Action action;
        final boolean optional;
        final String pkg;
        final boolean requiresHome;

        Entry(Action action, boolean optional, String pkg, boolean requiresHome) {
            this.action = action;
            this.optional = optional;
            this.pkg = pkg;
            this.requiresHome = requiresHome;
        }
    }

    private final String name;
    private final List<String> models;
    private final List<String> products;
    private final List<Entry> entries;

    private CleanupProfile(String name, List<String> models, List<String> products, List<Entry> entries) {
        this.name = name;
        this.models = models;
        this.products = products;
        this.entries = entries;
    }

    String name() { return name; }

    List<Entry> entries() { return entries; }

    /** True when the device's ro.product.model or ro.product.name equals one of the profile's {@code @match} values. */
    boolean matches(String model, String product) {
        return (model != null && models.contains(model)) || (product != null && products.contains(product));
    }

    static FetchResult<CleanupProfile> parse(String name, String text) {
        if (name == null) return FetchResult.err("profile name is null");
        if (text == null) return FetchResult.err("profile " + name + ": text is null");
        List<String> models = new ArrayList<>();
        List<String> products = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            int hash = lines[i].indexOf('#');
            String body = (hash >= 0 ? lines[i].substring(0, hash) : lines[i]).trim();
            if (body.isEmpty()) continue;
            String[] tok = body.split("\\s+");
            if (tok[0].equals("@match")) {
                String err = parseMatch(tok, models, products);
                if (err != null) return FetchResult.err(name + " line " + no + ": " + err);
                continue;
            }
            FetchResult<Entry> e = parseEntry(tok);
            if (!e.isOk()) return FetchResult.err(name + " line " + no + ": " + e.error());
            if (!seen.add(e.value().pkg)) return FetchResult.err(name + " line " + no + ": duplicate package " + e.value().pkg);
            entries.add(e.value());
        }
        if (models.isEmpty() && products.isEmpty()) return FetchResult.err(name + ": no @match line");
        return FetchResult.ok(new CleanupProfile(name, Collections.unmodifiableList(models),
                Collections.unmodifiableList(products), Collections.unmodifiableList(entries)));
    }

    private static String parseMatch(String[] tok, List<String> models, List<String> products) {
        if (tok.length != 2) return "@match takes exactly one key=value";
        int eq = tok[1].indexOf('=');
        if (eq <= 0 || eq == tok[1].length() - 1) return "bad @match: " + tok[1];
        String key = tok[1].substring(0, eq);
        String value = tok[1].substring(eq + 1);
        if (key.equals("model")) models.add(value);
        else if (key.equals("product")) products.add(value);
        else return "unknown @match key: " + key;
        return null;
    }

    private static FetchResult<Entry> parseEntry(String[] tok) {
        int idx = 0;
        Action action = Action.DISABLE;
        boolean optional = false;
        String first = tok[0];
        boolean q = first.endsWith("?");
        Action word = Action.fromWord(q ? first.substring(0, first.length() - 1) : first);
        if (word != null) {
            action = word;
            optional = q;
            idx = 1;
        }
        if (idx >= tok.length) return FetchResult.err("missing package");
        FetchResult<PackageName> pkg = PackageName.parse(tok[idx]);
        if (!pkg.isOk()) return FetchResult.err(pkg.error());
        boolean requiresHome = false;
        for (int j = idx + 1; j < tok.length; j++) {
            if (tok[j].equals("requires=home")) requiresHome = true;
            else return FetchResult.err("unknown option: " + tok[j]);
        }
        return FetchResult.ok(new Entry(action, optional, pkg.value().value(), requiresHome));
    }
}
