package io.tesseraql.yaml.lint;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A lint finding for an app (design ch. 18 {@code lint}, 28.17, 33).
 *
 * <p>The severity is an enum (docs/lint-restructure.md decision 4) because a rule chooses
 * between two answers and a free string let a typo pass as a third. Everything outside the
 * linter still reads the string: {@link #severity()} is the serialized property, so the CLI's
 * JSON document, the ops and Studio rows and the VS Code extension see the shape they always
 * did.
 *
 * @param code    the TQL error/lint code
 * @param level   whether the rule refuses the app or only warns about it
 * @param source  the app-relative source file
 * @param message human-readable description
 * @param line    1-based line in {@code source} when the rule can locate itself, else null
 *                (authoring feedback, roadmap Phase 43: positions are best-effort — document
 *                rules point at the first occurrence of the offending key)
 * @param column  1-based column on {@code line}, else null
 */
@JsonPropertyOrder({"code", "severity", "source", "message", "line", "column", "error"})
public record LintFinding(String code, @JsonIgnore Severity level, String source, String message,
        Integer line, Integer column) {

    /** What a rule does to the app it fired on. */
    public enum Severity {

        /** The app is refused: the build fails and the runtime will not serve it. */
        ERROR("error"),

        /** The app loads; the rule reports something the author probably did not mean. */
        WARNING("warning");

        private final String wire;

        Severity(String wire) {
            this.wire = wire;
        }

        /** The lowercase wire form every consumer outside the linter reads. */
        @Override
        public String toString() {
            return wire;
        }
    }

    /** A position-less finding — the shape every rule used before positions existed. */
    public LintFinding(String code, Severity level, String source, String message) {
        this(code, level, source, message, null, null);
    }

    /** {@code error} or {@code warning} — the wire form, and the one JSON property. */
    @JsonProperty("severity")
    public String severity() {
        return level.toString();
    }

    public boolean isError() {
        return level == Severity.ERROR;
    }

    /** {@code source[:line[:column]]} — the clickable form for CLI/editor output. */
    public String location() {
        if (line == null) {
            return source;
        }
        return column == null ? source + ":" + line : source + ":" + line + ":" + column;
    }
}
