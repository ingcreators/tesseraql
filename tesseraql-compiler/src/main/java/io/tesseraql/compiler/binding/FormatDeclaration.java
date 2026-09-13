package io.tesseraql.compiler.binding;

/**
 * One formatting key of a file recipe as the route declared it and as the app configuration
 * defaults it: {@code declared} is the route's {@code locale:}/{@code timezone:} — a literal or
 * a request source — and {@code configDefault} is the literal {@code tesseraql.files.<key>}
 * behind it. Both travel to the binder because the fallback is a per-request decision: a source
 * that resolves to nothing falls to the configured literal, and only then to the platform.
 *
 * <p>The compiler used to collapse the two into one string, so a route with a source could
 * never reach the configuration — the "unset values fall back to tesseraql.files.locale/timezone"
 * the feature was released with was true only for routes that declared nothing.
 *
 * @param key           {@code locale} or {@code timezone} — the field-error code a refusal carries
 * @param declared      the route's declaration, or null when it declares none
 * @param configDefault the app-wide literal, or null when the key is unset
 */
public record FormatDeclaration(String key, String declared, String configDefault) {

    /** Blank strings are "not declared" on both sides, as {@code ColumnValues} already treats them. */
    public static FormatDeclaration of(String key, String declared, String configDefault) {
        return new FormatDeclaration(key, blankToNull(declared), blankToNull(configDefault));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
