package com.johnmak.atvlauncher;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses `dumpsys meminfo -c` into PSS (KB) per package. Android 14+ prints two blocks (first RSS, then PSS), each
 * made of `oom,` / `proc,` / `cat,` lines; the PSS block is the last one, so only the last block's `proc,` lines count.
 * Line: `proc,{oomCategory},{processName},{pid},{kb},{swapKb|N/A},{a|e}`; sub-processes (`pkg:name`) fold into `pkg`.
 */
final class MeminfoParser {
    private static final int FIELDS = 7;

    private MeminfoParser() {}

    static FetchResult<Map<String, Long>> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return FetchResult.err("meminfo output is empty");
        Map<String, Long> block = null;
        boolean afterCat = true;
        for (String line : raw.split("\n")) {
            String l = line.trim();
            if (l.startsWith("oom,") && afterCat) {
                block = new HashMap<>();
                afterCat = false;
            } else if (l.startsWith("cat,")) {
                afterCat = true;
            } else if (l.startsWith("proc,")) {
                if (block == null) block = new HashMap<>();
                String problem = add(block, l);
                if (problem != null) return FetchResult.err(problem);
            }
        }
        if (block == null || block.isEmpty()) return FetchResult.err("meminfo output has no proc lines");
        return FetchResult.ok(block);
    }

    /** Adds the line's PSS to its package; returns an error message, or null on success. */
    private static String add(Map<String, Long> into, String line) {
        String[] f = line.split(",", -1);
        if (f.length != FIELDS) return "meminfo proc line has " + f.length + " fields: " + line;
        String pkg = ProcessNames.packageOf(f[2]);
        if (pkg.isEmpty()) return "meminfo proc line has no name: " + line;
        long kb;
        try {
            kb = Long.parseLong(f[4]);
        } catch (NumberFormatException e) {
            return "meminfo proc line has bad PSS: " + line;
        }
        if (kb < 0) return "meminfo proc line has negative PSS: " + line;
        Long old = into.get(pkg);
        into.put(pkg, old == null ? kb : old + kb);
        return null;
    }
}
