package io.tesseraql.core.telemetry;

import java.util.List;

/**
 * Fans spans out to several tracers at once (design ch. 25.4), so the same spans can feed the
 * in-process ring (for the Operations UI) and an exporter (such as OpenTelemetry/OTLP) together.
 *
 * <p>{@link Span#context()} returns the first delegate's context, so parent propagation follows the
 * first tracer (typically the in-process {@link RingTracer}).
 */
public final class CompositeTracer implements Tracer {

    private final List<Tracer> delegates;

    public CompositeTracer(Tracer... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public Span start(String name) {
        return start(name, null);
    }

    @Override
    public Span start(String name, SpanContext parent) {
        if (delegates.isEmpty()) {
            return NoopTracer.INSTANCE.start(name);
        }
        // The first delegate assigns the shared identity; keyed delegates (e.g. OpenTelemetry) reuse
        // it so they reconstruct the same parent/child hierarchy from the propagated context.
        Span primary = delegates.get(0).start(name, parent);
        SpanContext identity = primary.context();
        List<Span> spans = new java.util.ArrayList<>();
        spans.add(primary);
        for (int i = 1; i < delegates.size(); i++) {
            Tracer delegate = delegates.get(i);
            if (delegate instanceof KeyedTracer keyed && identity != null) {
                spans.add(keyed.start(name, parent, identity));
            } else {
                spans.add(delegate.start(name, parent));
            }
        }
        return new CompositeSpan(List.copyOf(spans));
    }

    private static final class CompositeSpan implements Span {
        private final List<Span> spans;

        CompositeSpan(List<Span> spans) {
            this.spans = spans;
        }

        @Override
        public Span attribute(String key, Object value) {
            spans.forEach(span -> span.attribute(key, value));
            return this;
        }

        @Override
        public void recordError(Throwable error) {
            spans.forEach(span -> span.recordError(error));
        }

        @Override
        public SpanContext context() {
            for (Span span : spans) {
                SpanContext context = span.context();
                if (context != null) {
                    return context;
                }
            }
            return null;
        }

        @Override
        public void end() {
            spans.forEach(Span::end);
        }
    }
}
