package io.tesseraql.runtime;

import org.apache.camel.builder.RouteBuilder;

/**
 * Drives the {@code db-poll} messaging transport (roadmap Phase 27): a timer drains every
 * subscribed channel on the backstop interval, claiming messages with {@code SKIP LOCKED} off the
 * durable {@code tql_event} table. Unlike {@link PgNotifyListener} it adds no low-latency wake — it
 * is the portable floor that works on every dialect (and on PostgreSQL behind a transaction-pooling
 * proxy that breaks {@code LISTEN}). Latency is the poll period; correctness and at-least-once
 * delivery are identical, because durability lives in the table either way.
 */
final class QueuePollRouteBuilder extends RouteBuilder {

    private final QueueConsumer consumer;
    private final long periodMs;

    QueuePollRouteBuilder(QueueConsumer consumer, long periodMs) {
        this.consumer = consumer;
        this.periodMs = periodMs;
    }

    @Override
    public void configure() {
        from("timer:tql-queue-poll?period=" + periodMs + "&delay=" + periodMs)
                .routeId("system.queue.poll")
                .process(exchange -> consumer.drainAll());
    }
}
