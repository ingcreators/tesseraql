package io.tesseraql.core.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link Tracer} that records finished spans, for diagnostics and tests (design ch. 25).
 */
public final class RecordingTracer implements Tracer {

    /** A finished span snapshot. */
    public record RecordedSpan(String name, Map<String, Object> attributes, boolean error) {
    }

    private final List<RecordedSpan> spans = new ArrayList<>();

    @Override
    public synchronized Span start(String name) {
        return new RecordingSpan(name);
    }

    public synchronized List<RecordedSpan> spans() {
        return List.copyOf(spans);
    }

    private synchronized void record(RecordedSpan span) {
        spans.add(span);
    }

    private final class RecordingSpan implements Span {
        private final String name;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private boolean error;

        RecordingSpan(String name) {
            this.name = name;
        }

        @Override
        public Span attribute(String key, Object value) {
            attributes.put(key, value);
            return this;
        }

        @Override
        public void recordError(Throwable error) {
            this.error = true;
        }

        @Override
        public void end() {
            record(new RecordedSpan(name, Map.copyOf(attributes), error));
        }
    }
}
