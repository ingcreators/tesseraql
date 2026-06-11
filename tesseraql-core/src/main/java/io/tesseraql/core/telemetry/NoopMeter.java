package io.tesseraql.core.telemetry;

/** A {@link Meter} that records nothing (design ch. 25). */
public final class NoopMeter implements Meter {

    public static final NoopMeter INSTANCE = new NoopMeter();

    private static final Counter NOOP_COUNTER = (delta, attributes) -> {
        // no-op
    };

    @Override
    public Counter counter(String name) {
        return NOOP_COUNTER;
    }
}
