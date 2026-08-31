package io.tesseraql.pipeline;

/**
 * The computed page window a paginated query executes under (roadmap Phase 41): validated and
 * bounded by the compiler's page binder from the request's {@code page}/{@code size} (offset
 * strategy) or {@code after} cursor (keyset), consumed by the SQL producer, which appends the
 * dialect's pagination clause, fetches one extra row for {@code hasNext}, optionally counts,
 * and publishes the {@code page} context entry.
 *
 * @param number 1-based page number (offset strategy; 1 for keyset)
 * @param size   the page size after bounding
 * @param offset rows to skip (0 for keyset)
 * @param count  whether to run the total-count wrapper
 * @param by     the keyset cursor columns in declaration order (null for offset); a composite
 *        cursor mints one opaque row token (docs/list-surface.md decision 5)
 */
public record PageRequest(long number, int size, long offset, boolean count,
        java.util.List<String> by) {
}
