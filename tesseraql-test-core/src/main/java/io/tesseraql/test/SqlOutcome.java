package io.tesseraql.test;

import java.util.List;
import java.util.Map;

/**
 * The outcome of executing a 2-way SQL file: the result rows of a query (including a write
 * with a {@code RETURNING} clause), or the affected-row count of a plain write — exactly one
 * of the two is non-null.
 */
record SqlOutcome(List<Map<String, Object>> rows, Integer updateCount) {
}
