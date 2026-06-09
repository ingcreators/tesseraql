package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Transactional outbox declaration for a command route (design ch. 39.2.1). The event is written
 * atomically with the command's SQL change.
 *
 * @param eventType     the event type, e.g. {@code USER_DISABLED}
 * @param aggregateType the aggregate type, e.g. {@code User}
 * @param aggregateId   a source expression for the aggregate id, e.g. {@code body.name}
 * @param payload       map of payload key to source expression
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboxSpec(String eventType, String aggregateType, String aggregateId,
        Map<String, String> payload) {

    public OutboxSpec {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
