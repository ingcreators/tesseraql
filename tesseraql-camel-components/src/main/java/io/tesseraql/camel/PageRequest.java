package io.tesseraql.camel;

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
 * @param by     the keyset cursor column (null for offset)
 */
public record PageRequest(long number, int size, long offset, boolean count, String by) {
}
