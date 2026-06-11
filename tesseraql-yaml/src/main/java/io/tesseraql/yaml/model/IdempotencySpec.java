package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route idempotency declaration (design ch. 39.5). Presence of this block enables idempotency.
 *
 * @param required whether the {@code Idempotency-Key} header is mandatory
 * @param scope    key scope override; defaults to the route id
 * @param ttl      record time-to-live (e.g. {@code 24h}); defaults to 24h
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdempotencySpec(Boolean required, String scope, String ttl) {

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
