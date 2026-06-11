package io.tesseraql.core.telemetry;

import java.util.List;

/**
 * A queryable record of recent spans collected in-process for operational diagnostics
 * (design ch. 26.11). Implemented by {@link RingTracer}; an empty instance is used when in-process
 * trace collection is not active.
 */
public interface TraceLog {

    /** The retained spans, most recent first. */
    List<SpanSample> recentSpans();

    /** A trace log that holds nothing. */
    static TraceLog empty() {
        return List::of;
    }
}
