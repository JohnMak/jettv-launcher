package com.johnmak.atvlauncher;

/** Pure judgement of a package-management command's result: exit 0 AND the verb's success marker. */
final class PmOutputParser {
    private PmOutputParser() {}

    /** ok(trimmed output) on success; otherwise a COMMAND_FAILED {@link ShellError} carrying the output. */
    static FetchResult<String> parse(ShellCommand.Verb verb, int exitCode, String output) {
        String marker = verb.successMarker();
        if (marker == null) throw new IllegalArgumentException(verb + " is not a package-management verb");
        String text = output == null ? "" : output.trim();
        boolean matches = marker.isEmpty() ? text.isEmpty() : text.contains(marker);
        if (exitCode == 0 && matches) return FetchResult.ok(text);
        return ShellError.fail(ShellError.COMMAND_FAILED, verb + " rc=" + exitCode + " " + text);
    }
}
