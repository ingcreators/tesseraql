package io.tesseraql.core.sql;

import java.util.regex.Pattern;

/**
 * The identifier contract (docs/unicode-identifiers.md): what a table, column, or alias may
 * look like when it lands verbatim in SQL text.
 *
 * <p>There were a dozen answers. Decision tables, calendars, workflow stamps, scope aliases,
 * and the DuckDB attach lints each compiled their own {@code [A-Za-z_][A-Za-z0-9_]*}, so the
 * question "is this a legal name" had as many answers as call sites — and every one of them
 * silently excluded the scripts half the world writes schemas in. All of them ask here now,
 * so there is one answer to change.
 *
 * <p>The pattern doubles as the injection defense: identifiers are never quoted, so the
 * character class is what keeps a "name" from being a fragment. Unicode letters and digits
 * cannot close a quote, open a comment, or terminate a statement, which is why widening from
 * ASCII preserves the property the old patterns enforced. Nothing here bounds length — the
 * engines count bytes, their limits differ, and their own errors are authoritative.
 */
public final class SqlIdentifiers {

    /**
     * Regex source for one identifier — a Unicode letter or underscore, then Unicode
     * letters, digits, or underscores — for embedding in larger patterns.
     */
    public static final String IDENTIFIER = "[\\p{L}_][\\p{L}\\p{N}_]*";

    /** Regex source for an optionally schema-qualified identifier ({@code name} or {@code schema.name}). */
    public static final String DOTTED = IDENTIFIER + "(?:\\." + IDENTIFIER + ")?";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(IDENTIFIER);
    private static final Pattern DOTTED_PATTERN = Pattern.compile(DOTTED);

    private SqlIdentifiers() {
    }

    /** Whether {@code candidate} is a plain identifier. */
    public static boolean isIdentifier(String candidate) {
        return candidate != null && IDENTIFIER_PATTERN.matcher(candidate).matches();
    }

    /** Whether {@code candidate} is a plain identifier, optionally schema-qualified. */
    public static boolean isDotted(String candidate) {
        return candidate != null && DOTTED_PATTERN.matcher(candidate).matches();
    }
}
