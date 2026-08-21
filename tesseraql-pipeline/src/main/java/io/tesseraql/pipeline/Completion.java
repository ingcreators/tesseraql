package io.tesseraql.pipeline;

/**
 * Work that runs when an exchange is done, whichever way it ended
 * (docs/camel-removal.md structural decision 2).
 *
 * <p>This was {@code org.apache.camel.spi.Synchronization}, and the campaign counted its uses
 * before replacing it: five, all of them registrations rather than questions — the route audit row,
 * the per-route concurrency permit, the lane permit, the telemetry span, and the SQL step's
 * streamed body. Every one leaks or goes missing on the error path if nobody runs it, and the
 * failure is silent, which is why this is a named type rather than a callback pair.
 */
public interface Completion {

    /** The exchange finished, including a failure the pipeline's clauses answered for. */
    void onComplete(Exchange exchange);

    /** The exchange failed with nothing to answer for it. */
    void onFailure(Exchange exchange);
}
