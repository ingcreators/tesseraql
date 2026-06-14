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
    void aPublishWithoutChannelOrTopicFailsFast() {
        assertThatThrownBy(() -> PublishEvents.compile("orders.create",
                new PublishSpec(null, "t", null, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2005");
        assertThatThrownBy(() -> PublishEvents.compile("orders.create",
                new PublishSpec("events", " ", null, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2005");
    }
}
