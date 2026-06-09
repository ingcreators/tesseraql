package io.tesseraql.core.dialect;

/**
 * Capability metadata for a SQL dialect (design ch. 42.2). Used by pagination helpers, upsert
 * generation, and lint in later phases.
 *
 * @param limitOffset    supports {@code LIMIT ... OFFSET}
 * @param offsetFetch    supports {@code OFFSET ... FETCH}
 * @param upsert         upsert style: {@code on-conflict}, {@code on-duplicate-key}, {@code merge}
 * @param identifierQuote the identifier quote character(s)
 */
public record DialectCapabilities(
        boolean limitOffset,
        boolean offsetFetch,
        String upsert,
        String identifierQuote) {
}
