package io.tesseraql.operations.outbox;

import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxEventSink;
import io.tesseraql.core.outbox.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers pending outbox events to a sink, marking each sent or failed (design ch. 39.2).
 * Intended to be run periodically by a scheduled route.
 */
public final class OutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final OutboxEventSink sink;

    public OutboxDispatcher(OutboxStore store, OutboxEventSink sink) {
        this.store = store;
        this.sink = sink;
    }

    /** Dispatches up to {@code limit} pending events; returns the number successfully sent. */
    public int dispatch(int limit) {
        int sent = 0;
        for (OutboxEvent event : store.listPending(limit)) {
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
