package io.tesseraql.runtime;

import io.tesseraql.core.outbox.OutboxEventSink;
import io.tesseraql.core.outbox.OutboxStore;
import io.tesseraql.operations.outbox.OutboxDispatcher;
import org.apache.camel.builder.RouteBuilder;

/**
 * Periodically delivers pending outbox events on a timer (design ch. 39.2.2). Enabled when
 * {@code tesseraql.outbox.dispatch.fixedDelay} is configured. Claims are scoped to the apps this
 * runtime hosts, so runtimes of different apps sharing one database never deliver each other's
 * events to the wrong sinks.
 */
final class OutboxDispatchRouteBuilder extends RouteBuilder {

    private static final int BATCH_SIZE = 500;

    private final OutboxStore store;
    private final OutboxEventSink sink;
    private final long periodMs;
    private final java.util.Set<String> hostedApps;

    OutboxDispatchRouteBuilder(OutboxStore store, OutboxEventSink sink, long periodMs,
            java.util.Set<String> hostedApps) {
        this.store = store;
        this.sink = sink;
        this.periodMs = periodMs;
        this.hostedApps = java.util.Set.copyOf(hostedApps);
    }

    @Override
    public void configure() {
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, sink, hostedApps);
        from("timer:tql-outbox-dispatch?period=" + periodMs + "&delay=" + periodMs)
                .routeId("system.outbox.dispatcher")
                .process(exchange -> dispatcher.dispatch(BATCH_SIZE));
    }
}
