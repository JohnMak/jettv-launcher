package com.johnmak.atvlauncher;

/** Pure judgement of a package-management command's result: exit 0 AND the verb's success marker. */
final class PmOutputParser {
    private PmOutputParser() {}

    /**
     * ok(trimmed stdout) on success; otherwise a COMMAND_FAILED {@link ShellError} carrying the output. Verbs with an
     * empty marker succeed on exit 0 with empty stdout and no stderr other than "WARNING:" lines; the others need their
     * marker in the output.
     */
    static FetchResult<String> parse(ShellCommand.Verb verb, int exitCode, String stdout, String stderr) {
        String marker = verb.successMarker();
        if (marker == null) throw new IllegalArgumentException(verb + " is not a package-management verb");
        String out = stdout == null ? "" : stdout.trim();
        String err = stderr == null ? "" : stderr.trim();
        boolean matches;
        if (marker.isEmpty()) matches = out.isEmpty() && withoutWarnings(err).isEmpty();
        else matches = out.contains(marker) || err.contains(marker);
        if (exitCode == 0 && matches) return FetchResult.ok(out);
        String text = err.isEmpty() ? out : (out.isEmpty() ? err : out + "\n" + err);
        return ShellError.fail(ShellError.COMMAND_FAILED, verb + " rc=" + exitCode + " " + text);
    }

    private static String withoutWarnings(String stderr) {
        StringBuilder kept = new StringBuilder();
        for (String line : stderr.split("\n")) {
            if (!line.trim().isEmpty() && !line.startsWith("WARNING:")) kept.append(line);
        }
        return kept.toString().trim();
    }
}
