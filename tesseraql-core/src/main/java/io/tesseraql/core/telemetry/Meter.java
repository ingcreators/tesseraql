package io.tesseraql.core.telemetry;

import java.util.Map;

/**
 * Minimal metrics abstraction (design ch. 25.6, roadmap Phase 45). An OpenTelemetry-backed
 * implementation lives in tesseraql-observability; the JDK-only {@link AggregatingMeter} backs
 * the Prometheus exposition; a no-op is used when neither is wired.
 */
public interface Meter {

    /** Returns (or creates) a monotonic counter by name. */
    Counter counter(String name);

    /**
     * Returns (or creates) a latency histogram by name (roadmap Phase 45, decision point 9).
     * Values are milliseconds; implementations bucket them for percentile queries.
     */
    default Histogram histogram(String name) {
        return (value, attributes) -> {
        };
    }

    /** A monotonic counter. */
    interface Counter {
        void add(long delta, Map<String, String> attributes);

        default void increment(Map<String, String> attributes) {
            add(1, attributes);
        }
    }

    /** A latency histogram recording millisecond observations. */
    interface Histogram {
        void record(long valueMillis, Map<String, String> attributes);
    }
}
