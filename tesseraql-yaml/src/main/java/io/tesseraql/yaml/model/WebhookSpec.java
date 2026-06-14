package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code webhook:} block of a {@code webhook} route (roadmap Phase 26): the inbound-webhook
 * recipe verifies an HMAC-signed request and protects against replay before running the route's
 * SQL pipeline. The {@code provider} names a verifier configured under
 * {@code tesseraql.connectors.webhooks}, which holds the signing secret, the header names, and the
 * timestamp tolerance — so the route never carries a secret.
 *
 * @param provider the verifier name under {@code tesseraql.connectors.webhooks}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookSpec(String provider) {
}
