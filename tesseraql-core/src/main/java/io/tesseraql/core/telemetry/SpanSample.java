package io.tesseraql.core.telemetry;

import java.util.Map;

/**
 * A finished span captured for in-process trace diagnostics (design ch. 25.4, 26.11).
 *
 * @param name             the span name (e.g. {@code tesseraql.route}, {@code tesseraql.sql.execute})
 * @param attributes       span attributes recorded during execution
 * @param durationMs       wall-clock span duration in milliseconds
 * @param error            whether an error was recorded on the span
 * @param startedAtEpochMs when the span started, epoch milliseconds
 */
public record SpanSample(String name, Map<String, Object> attributes, long durationMs, boolean error,
        long startedAtEpochMs) {
}
