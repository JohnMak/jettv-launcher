package com.johnmak.atvlauncher;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses toybox `ps -A -o PID,TIME+,NAME` into cumulative CPU milliseconds per package. TIME+ is
 * `[[dd-]hh:]mm:ss.cc` (minutes may exceed 59 when no hours are shown). Kernel threads (`[kworker/0:1]`) are kept
 * under their bracketed name; they never match an app package. Sub-processes (`pkg:name`) fold into `pkg`.
 */
final class ProcessTimesParser {
    private static final Pattern HEADER = Pattern.compile("^\\s*PID\\s+TIME\\+\\s+NAME\\s*$");
    private static final Pattern ROW = Pattern.compile("^\\s*(\\d+)\\s+(\\S+)\\s+(.+?)\\s*$");
    private static final Pattern TIME = Pattern.compile("^(?:(?:(\\d+)-)?(\\d+):)?(\\d+):(\\d{2})\\.(\\d{2})$");

    private ProcessTimesParser() {}

    static FetchResult<Map<String, Long>> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return FetchResult.err("ps output is empty");
        String[] lines = raw.split("\n");
        if (!HEADER.matcher(lines[0]).matches()) return FetchResult.err("ps output has no 'PID TIME+ NAME' header: " + lines[0]);
        Map<String, Long> out = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            Matcher row = ROW.matcher(lines[i]);
            if (!row.matches()) return FetchResult.err("ps row is malformed: " + lines[i]);
            Matcher t = TIME.matcher(row.group(2));
            if (!t.matches()) return FetchResult.err("ps row has bad TIME+: " + lines[i]);
            long days = t.group(1) == null ? 0 : Long.parseLong(t.group(1));
            long hours = t.group(2) == null ? 0 : Long.parseLong(t.group(2));
            long minutes = Long.parseLong(t.group(3));
            long seconds = Long.parseLong(t.group(4));
            if (seconds > 59) return FetchResult.err("ps row has bad seconds: " + lines[i]);
            long millis = ((((days * 24 + hours) * 60 + minutes) * 60) + seconds) * 1000L + Long.parseLong(t.group(5)) * 10;
            out.merge(ProcessNames.packageOf(row.group(3)), millis, Long::sum);
        }
        if (out.isEmpty()) return FetchResult.err("ps output has no process rows");
        return FetchResult.ok(out);
    }
}
