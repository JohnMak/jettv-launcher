package com.alexmak.atvlauncher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses `gmpfUnit` getter output of the form `return_data : {N }`. */
final class GmpfParser {
    private static final Pattern VALUE = Pattern.compile("return_data\\s*:\\s*\\{\\s*(\\d+)\\s*\\}");

    private GmpfParser() {}

    static FetchResult<Integer> parseValue(String rawOutput) {
        if (rawOutput == null || rawOutput.trim().isEmpty()) {
            return FetchResult.err("gmpfUnit output is empty");
        }
        Matcher m = VALUE.matcher(rawOutput);
        if (m.find()) {
            try {
                return FetchResult.ok(Integer.valueOf(m.group(1)));
            } catch (NumberFormatException e) {
                return FetchResult.err("gmpfUnit value out of int range: " + m.group(1));
            }
        }
        if (rawOutput.contains("inaccessible")) {
            return FetchResult.err("gmpfUnit inaccessible: " + rawOutput.trim());
        }
        if (rawOutput.contains("not found")) {
            return FetchResult.err("gmpfUnit not found: " + rawOutput.trim());
        }
        return FetchResult.err("gmpfUnit output has no 'return_data : {N }' value: " + rawOutput.trim());
    }
}
