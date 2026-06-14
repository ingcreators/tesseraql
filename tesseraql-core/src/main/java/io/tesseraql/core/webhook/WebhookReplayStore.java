package io.tesseraql.core.webhook;

import java.time.Instant;

/**
 * Replay protection for the inbound webhook recipe (roadmap Phase 26): records a delivery id the
 * first time it is seen and rejects it on any later delivery until its timestamp tolerance lapses.
 *
 * <p>The store is shared across nodes (a JDBC table), so a replayed delivery is rejected on every
 * node sharing the database, the same way SAML assertion replay is bounded.
 */
public interface WebhookReplayStore {

    /**
     * Records {@code deliveryId} as seen until {@code expiresAt}; returns {@code true} the first
     * time and {@code false} if it was already recorded (a replay). Expired entries are pruned, so
     * a delivery id is only retained for the configured tolerance window.
     */
    boolean markSeen(String deliveryId, Instant expiresAt);
}
