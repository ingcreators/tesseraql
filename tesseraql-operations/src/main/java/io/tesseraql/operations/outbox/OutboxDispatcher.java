package io.tesseraql.operations.outbox;

import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxEventSink;
import io.tesseraql.core.outbox.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers pending outbox events to a sink, marking each sent or failed (design ch. 39.2).
 * Intended to be run periodically by a scheduled route. Events are claimed atomically per node
 * ({@link OutboxStore#claimPending}), so concurrent dispatchers on several nodes never deliver
 * the same event at the same time.
 *
 * <p>A failed event retries on later polls until its attempts reach {@code maxAttempts}; then it
 * is dead-lettered (roadmap Phase 20) — it stops retrying and stays visible to operators in the
 * operations console until redelivered or swept.
 */
public final class OutboxDispatcher {

    /** The default delivery-attempt ceiling before an event is dead-lettered. */
    public static final int DEFAULT_MAX_ATTEMPTS = 10;

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final OutboxEventSink sink;
    private final java.util.Set<String> hostedApps;
    private final int maxAttempts;

    public OutboxDispatcher(OutboxStore store, OutboxEventSink sink) {
        this(store, sink, java.util.Set.of());
    }

    /**
     * @param hostedApps the apps this runtime hosts; when non-empty, only their events are
     *                   claimed, so runtimes of different apps sharing one database never
     *                   deliver each other's events to the wrong sinks
     */
    public OutboxDispatcher(OutboxStore store, OutboxEventSink sink,
            java.util.Set<String> hostedApps) {
        this(store, sink, hostedApps, DEFAULT_MAX_ATTEMPTS);
    }

    /** @param maxAttempts the delivery-attempt ceiling before an event is dead-lettered */
    public OutboxDispatcher(OutboxStore store, OutboxEventSink sink,
            java.util.Set<String> hostedApps, int maxAttempts) {
        this.store = store;
        this.sink = sink;
        this.hostedApps = java.util.Set.copyOf(hostedApps);
        this.maxAttempts = maxAttempts;
    }

    /** Dispatches up to {@code limit} pending events; returns the number successfully sent. */
    public int dispatch(int limit) {
        int sent = 0;
        for (OutboxEvent event : store.claimPending(limit, hostedApps)) {
            try {
                sink.send(event);
                store.markSent(event.id());
                sent++;
            } catch (Exception ex) {
                // event.attempts() counts completed attempts; this failure is one more.
                if (event.attempts() + 1 >= maxAttempts) {
                    LOG.warn("Outbox delivery failed for {} ({} attempts); dead-lettering: {}",
                            event.id(), event.attempts() + 1, ex.getMessage());
                    store.markDead(event.id(), ex.getMessage());
                } else {
                    LOG.warn("Outbox delivery failed for {}: {}", event.id(), ex.getMessage());
                    store.markFailed(event.id(), ex.getMessage());
                }
            }
        }
        return sent;
    }
}
