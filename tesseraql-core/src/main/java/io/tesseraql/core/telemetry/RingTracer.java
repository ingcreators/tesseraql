package io.tesseraql.core.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link Tracer} that keeps the most recent finished spans in a bounded in-memory ring, exposing
 * them via {@link TraceLog} for the Operations UI (design ch. 26.11). Safe for concurrent use; the
 * oldest span is discarded when the ring is full.
 */
public final class RingTracer implements Tracer, TraceLog {

    private final int capacity;
    private final ArrayDeque<SpanSample> ring;
    private final AtomicLong ids = new AtomicLong();

    public RingTracer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new ArrayDeque<>(capacity);
    }

    @Override
    public Span start(String name) {
        return start(name, null);
    }

    @Override
    public Span start(String name, SpanContext parent) {
        String spanId = Long.toHexString(ids.incrementAndGet());
        String traceId = parent != null
                ? parent.traceId()
                : Long.toHexString(ids.incrementAndGet());
        String parentSpanId = parent != null ? parent.spanId() : null;
        return new RingSpan(name, traceId, spanId, parentSpanId,
                System.nanoTime(), System.currentTimeMillis());
    }

    @Override
    public synchronized List<SpanSample> recentSpans() {
        List<SpanSample> snapshot = new ArrayList<>(ring);
        Collections.reverse(snapshot);
        return List.copyOf(snapshot);
    }

    private synchronized void record(SpanSample sample) {
        if (ring.size() == capacity) {
            ring.removeFirst();
        }
        ring.addLast(sample);
    }

    private final class RingSpan implements Span {
        private final String name;
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final long startNanos;
        private final long startedAtEpochMs;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private boolean error;

        RingSpan(String name, String traceId, String spanId, String parentSpanId,
                long startNanos, long startedAtEpochMs) {
            this.name = name;
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.startNanos = startNanos;
            this.startedAtEpochMs = startedAtEpochMs;
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
        public SpanContext context() {
            return new SpanContext(traceId, spanId);
        }

        @Override
        public void end() {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            record(new SpanSample(name, traceId, spanId, parentSpanId,
                    Map.copyOf(attributes), durationMs, error, startedAtEpochMs));
        }
    }
}
