package io.tesseraql.core.telemetry;

/**
 * A tracing span (design ch. 25.4). Implements {@link AutoCloseable} so it can be used in a
 * try-with-resources block; {@link #close()} ends the span.
 */
public interface Span extends AutoCloseable {

    /** Adds an attribute to the span and returns this span for chaining. */
    Span attribute(String key, Object value);

    /** Records an error/exception on the span. */
    void recordError(Throwable error);

    /** Ends the span. */
    void end();

    @Override
    default void close() {
        end();
    }
}
