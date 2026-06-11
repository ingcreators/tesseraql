package io.tesseraql.core.diag;

/**
 * A single SQL execution sample for the slow-query log (design ch. 26.11).
 *
 * @param sqlId             the SQL source identifier (the file path)
 * @param mode              the execution mode ({@code query}, {@code update}, ...)
 * @param durationMs        wall-clock execution time in milliseconds
 * @param rowCount          rows returned (query) or affected (update)
 * @param startedAtEpochMs  when the execution started, epoch milliseconds
 */
public record SqlExecution(String sqlId, String mode, long durationMs, long rowCount,
        long startedAtEpochMs) {
}
