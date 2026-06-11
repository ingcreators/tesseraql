package io.tesseraql.core.telemetry;

import java.util.Map;

/**
 * Minimal metrics abstraction (design ch. 25.6). An OpenTelemetry-backed implementation lives in
 * tesseraql-observability; a no-op is used when observability is disabled.
 */
public interface Meter {

    /** Returns (or creates) a monotonic counter by name. */
    Counter counter(String name);

    /** A monotonic counter. */
    interface Counter {
        void add(long delta, Map<String, String> attributes);

        default void increment(Map<String, String> attributes) {
            add(1, attributes);
        }
    }
}
