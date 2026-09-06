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
 * ASCII preserves the property the old patterns enforced. Combining marks extend that argument
 * rather than weakening it, and it is measured rather than assumed: of the 2488 code points in
 * {@code \p{Mn}} and {@code \p{Mc}}, none is ASCII, none normalizes under any form to an ASCII
 * non-alphanumeric, and none case-maps to one. Nothing here bounds length — the engines count
 * bytes, their limits differ, and their own errors are authoritative.
 *
 * <p>The class is deliberately narrower than the 2-way bind lexer, which is
 * {@code Character.isJavaIdentifierPart}: the lexer also admits {@code $} and connector
 * punctuation, and {@code $} opens dollar-quoting in PostgreSQL. The lexer is not the
 * specification; this is.
 */
public final class SqlIdentifiers {

    /**
     * The characters a name may carry after its first: Unicode letters, combining marks,
     * digits and underscore.
     *
     * <p>The marks are what every abugida requires and what decomposed (NFD) text produces for
     * a script that has a composed form — the form macOS emits. A call site that needs a variant
     * of the class composes this rather than writing its own; the copies were the whole defect
     * (docs/two-way-sql-parser.md decision 11).
     */
    public static final String PART = "[\\p{L}\\p{Mn}\\p{Mc}\\p{N}_]";

    /**
     * Regex source for one identifier — a Unicode letter or underscore, then letters,
     * combining marks, digits, or underscores — for embedding in larger patterns.
     *
     * <p>A mark may not <em>start</em> a name: every real name begins with a letter, and a
     * leading combining mark is a rendering trick. {@code \p{Me}} (enclosing marks) is excluded
     * as display-only, and {@code \p{Cf}} (ZWJ, ZWNJ) because an invisible character in a name
     * that lands unquoted in SQL text is a spoofing surface.
     */
    public static final String IDENTIFIER = "[\\p{L}_]" + PART + "*";

    /** Regex source for an optionally schema-qualified identifier ({@code name} or {@code schema.name}). */
    public static final String DOTTED = IDENTIFIER + "(?:\\." + IDENTIFIER + ")?";

    /**
     * A {@code {name}} placeholder whose name is one identifier: URL path parameters,
     * message placeholders, and view link templates all extract through this — an
     * unmatched placeholder passes through silently on those paths, so the extractor
     * accepting exactly what the validators accept is the whole point.
     */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{(" + IDENTIFIER + ")}");

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
