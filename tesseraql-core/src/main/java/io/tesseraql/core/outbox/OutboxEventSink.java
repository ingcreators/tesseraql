package io.tesseraql.core.outbox;

/**
 * Delivers an outbox event to its destination (design ch. 39.2). Implementations send to Kafka,
 * JMS, HTTP, etc.; throwing signals a delivery failure so the dispatcher can retry.
 */
@FunctionalInterface
public interface OutboxEventSink {
    void send(OutboxEvent event) throws Exception;
}
