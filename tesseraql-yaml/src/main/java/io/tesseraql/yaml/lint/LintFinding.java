package io.tesseraql.yaml.lint;

/**
 * A lint finding for an app (design ch. 18 {@code lint}, 28.17, 33).
 *
 * @param code     the TQL error/lint code
 * @param severity {@code error} or {@code warning}
 * @param source   the app-relative source file
 * @param message  human-readable description
 * @param line     1-based line in {@code source} when the rule can locate itself, else null
 *                 (authoring feedback, roadmap Phase 43: positions are best-effort — document
 *                 rules point at the first occurrence of the offending key)
 * @param column   1-based column on {@code line}, else null
 */
public record LintFinding(String code, String severity, String source, String message,
        Integer line, Integer column) {

    /** A position-less finding — the shape every rule used before positions existed. */
    public LintFinding(String code, String severity, String source, String message) {
        this(code, severity, source, message, null, null);
    }

    public boolean isError() {
        return "error".equals(severity);
    }

    /** {@code source[:line[:column]]} — the clickable form for CLI/editor output. */
    public String location() {
        if (line == null) {
            return source;
        }
        return column == null ? source + ":" + line : source + ":" + line + ":" + column;
    }
}
