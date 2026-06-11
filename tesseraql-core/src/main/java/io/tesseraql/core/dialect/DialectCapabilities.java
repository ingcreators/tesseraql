package io.tesseraql.core.dialect;

/**
 * Capability metadata for a SQL dialect (design ch. 42.2). Used by pagination helpers, upsert
 * generation, and lint in later phases.
 *
 * @param limitOffset    supports {@code LIMIT ... OFFSET}
 * @param offsetFetch    supports {@code OFFSET ... FETCH}
 * @param upsert         upsert style: {@code on-conflict}, {@code on-duplicate-key}, {@code merge}
 * @param identifierQuote the identifier quote character(s)
 * @param generatedKeys  how generated keys come back (roadmap Phase 18): {@code columns} (the
 *                       driver honors requested column names — PostgreSQL appends
 *                       {@code RETURNING}, Oracle uses {@code RETURNING INTO}) or {@code auto}
 *                       (only the auto-increment / identity value comes back — MySQL, SQL Server)
 */
public record DialectCapabilities(
        boolean limitOffset,
        boolean offsetFetch,
        String upsert,
        String identifierQuote,
        String generatedKeys) {

    /** Whether the JDBC driver honors requested generated-key column names. */
    public boolean generatedKeyColumns() {
        return "columns".equals(generatedKeys);
    }
}
