package io.tesseraql.runtime;

import io.tesseraql.core.messaging.EventChannelStore;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxEventSink;
import io.tesseraql.yaml.messaging.MessagingChannels;
import io.tesseraql.yaml.messaging.PublishEvents;

/**
 * Relays {@code EVENT} outbox events onto a messaging channel (roadmap Phase 27): the producer
 * side of the {@code publish:} block. Other event types are left for the sinks composed around this
 * one (notifications, extension sinks). A publish failure throws, so the outbox dispatcher's
 * at-least-once retry and dead-letter policy applies — exactly like the notification sink.
 *
 * <p>For the built-in {@code pg-notify} transport, publishing is a durable {@code tql_event} insert
 * plus a PostgreSQL {@code NOTIFY} in one transaction, so a listening consumer wakes the instant the
 * row is visible. The {@link OutboxEventSink} contract is the seam a broker transport (Kafka, JMS)
 * later plugs into, without changing the {@code publish:} YAML.
 */
final class ChannelPublishSink implements OutboxEventSink {

    private final MessagingChannels channels;
    private final EventChannelStore store;

    ChannelPublishSink(MessagingChannels channels, EventChannelStore store) {
        this.channels = channels;
        this.store = store;
    }

    @Override
    public void send(OutboxEvent event) {
        if (!PublishEvents.isEvent(event)) {
            return;
        }
        PublishEvents.Envelope envelope = PublishEvents.parse(event.payloadJson());
        // require() fails fast on an unknown channel; lint catches it at build, and at runtime the
        // throw routes the event through the outbox's retry/dead-letter path rather than dropping it.
        channels.require(envelope.channel());
        store.publish(envelope.channel(), envelope.topic(), envelope.key(),
                PublishEvents.payloadJson(envelope.payload()));
    }
}
