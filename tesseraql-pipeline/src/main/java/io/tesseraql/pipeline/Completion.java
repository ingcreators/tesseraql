package io.tesseraql.pipeline;

/**
 * Work that runs when an exchange is done, whichever way it ended
 * (docs/vertx-native.md structural decision 5).
 *
 * <p>This was {@code org.apache.camel.spi.Synchronization}, and it kept that interface's
 * complete/failed pair until the campaign measured which branch runs: the pipeline answers every
 * failure it has a clause for <em>before</em> draining — the exception moves to the
 * {@link TesseraqlProperties#EXCEPTION_CAUGHT} property when the envelope takes it — so the
 * failure branch was unreachable, and the one place whose two bodies differed had never run the
 * differing half. One method cannot be registered against the wrong branch.
 *
 * <p>The five registrations are the route audit row, the per-route concurrency permit, the lane
 * permit, the telemetry span, and the SQL step's streamed body. Every one leaks or goes missing
 * if nobody runs it, and the failure is silent, which is why this is a named type rather than a
 * callback: {@link Exchange#drain()} is the one place that runs them.
 */
@FunctionalInterface
public interface Completion {

    /**
     * The exchange finished. A failure a clause answered for is on
     * {@link TesseraqlProperties#EXCEPTION_CAUGHT}; one nothing answered for is still on
     * {@link Exchange#getException()}.
     */
    void onDone(Exchange exchange);
}
