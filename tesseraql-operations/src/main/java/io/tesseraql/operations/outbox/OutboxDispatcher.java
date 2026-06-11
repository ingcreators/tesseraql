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
 */
public final class OutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final OutboxEventSink sink;
    private final java.util.Set<String> hostedApps;

    public OutboxDispatcher(OutboxStore store, OutboxEventSink sink) {
        this(store, sink, java.util.Set.of());
    }

    /**
     * @param hostedApps the apps this runtime hosts; when non-empty, only their events (plus
     *                   untagged legacy rows) are claimed, so runtimes of different apps sharing
     *                   one database never deliver each other's events to the wrong sinks
     */
    public OutboxDispatcher(OutboxStore store, OutboxEventSink sink,
            java.util.Set<String> hostedApps) {
        this.store = store;
        this.sink = sink;
        this.hostedApps = java.util.Set.copyOf(hostedApps);
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
                LOG.warn("Outbox delivery failed for {}: {}", event.id(), ex.getMessage());
                store.markFailed(event.id(), ex.getMessage());
            }
        }
        return sent;
    }
}
