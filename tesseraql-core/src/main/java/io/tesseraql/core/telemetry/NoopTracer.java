package io.tesseraql.core.telemetry;

/**
 * A {@link Tracer} that does nothing, used when observability is disabled (design ch. 25).
 */
public final class NoopTracer implements Tracer {

    public static final NoopTracer INSTANCE = new NoopTracer();

    private static final Span NOOP_SPAN = new Span() {
        @Override
        public Span attribute(String key, Object value) {
            return this;
        }

        @Override
        public void recordError(Throwable error) {
            // no-op
        }

        @Override
        public void end() {
            // no-op
        }
    };

    @Override
    public Span start(String name) {
        return NOOP_SPAN;
    }
}
