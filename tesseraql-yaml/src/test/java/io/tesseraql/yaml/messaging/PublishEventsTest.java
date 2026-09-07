package io.tesseraql.yaml.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.model.PublishSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Compiles, encodes, and decodes a Phase 27 {@code publish:} declaration through the outbox. */
class PublishEventsTest {

    @Test
    void compilesResolvesAndRoundTripsAnEnvelope() {
        PublishSpec spec = new PublishSpec("events", "orders.created", "body.orderId",
                Map.of("orderId", "body.orderId", "total", "body.total"));
        PublishEvents.CompiledPublish compiled = PublishEvents.compile("orders.create", spec);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("body", Map.of("orderId", "O-1", "total", 1250));

        assertThat(compiled.resolveKey(context)).isEqualTo("O-1");
        assertThat(compiled.resolvePayload(context))
                .containsEntry("orderId", "O-1")
                .containsEntry("total", 1250);

        OutboxEvent event = compiled.build(context, "shop");
        assertThat(event.eventType()).isEqualTo(PublishEvents.EVENT_TYPE);
        assertThat(PublishEvents.isEvent(event)).isTrue();
        assertThat(event.appName()).isEqualTo("shop");

        PublishEvents.Envelope envelope = PublishEvents.parse(event.payloadJson());
        assertThat(envelope.channel()).isEqualTo("events");
        assertThat(envelope.topic()).isEqualTo("orders.created");
        assertThat(envelope.key()).isEqualTo("O-1");
        assertThat(envelope.payload()).containsEntry("orderId", "O-1");
    }

    @Test
    void aParsedEnvelopeKeepsThePayloadOrderItWasWrittenIn() {
        // The decoded payload is what payloadJson() serializes into the message body a subscriber
        // receives, so two identical events used to reach the broker as different bytes. Four
        // keys: every permutation of a three-key map is reachable, and these four have eight
        // reachable orders with the declared one absent (docs/deterministic-output.md).
        var envelope = PublishEvents.parse("""
                {"channel": "events", "topic": "orders", "payload":
                  {"orderId": 7, "status": "PLACED", "amount": 4200, "updatedAt": "2026-09-07"}}
                """);

        assertThat(envelope.payload().keySet())
                .containsExactly("orderId", "status", "amount", "updatedAt");
        assertThat(PublishEvents.payloadJson(envelope.payload())).isEqualTo(
                "{\"orderId\":7,\"status\":\"PLACED\",\"amount\":4200,"
                        + "\"updatedAt\":\"2026-09-07\"}");
    }

    @Test
    void aPayloadValueThatResolvedToNothingSurvivesDecoding() {
        var envelope = PublishEvents.parse(
                "{\"channel\": \"events\", \"payload\": {\"a\": 1, \"b\": null}}");

        assertThat(envelope.payload()).containsEntry("b", null);
    }

    @Test
    void aPublishWithoutChannelOrTopicFailsFast() {
        assertThatThrownBy(() -> PublishEvents.compile("orders.create",
                new PublishSpec(null, "t", null, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2010");
        assertThatThrownBy(() -> PublishEvents.compile("orders.create",
                new PublishSpec("events", " ", null, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2010");
    }
}
