package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.messaging.EventMessage;
import io.tesseraql.operations.messaging.JdbcEventChannelStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 27 store-level: the pg-notify durable event log claims with SKIP LOCKED (a claimed message
 * is not re-delivered), deduplicates by idempotency key, and dead-letters after the attempt ceiling.
 * The {@link PgNotifyListener} only decides <em>when</em> to drain; these are the durability
 * guarantees that make delivery at-least-once regardless of any wake signal.
 */
@Testcontainers
class EventChannelStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String CHANNEL = "events";
    private static final String TOPIC = "orders.created";

    JdbcEventChannelStore store;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        store = new JdbcEventChannelStore(dataSource);
        store.ensureSchema();
    }

    @Test
    void publishesClaimsAndConsumesAMessageOnce() {
        String id = store.publish(CHANNEL, TOPIC, "O-1", "{\"orderId\":\"O-1\"}", "user-admin");

        List<EventMessage> claimed = store.claim(CHANNEL, TOPIC, 10);
        assertThat(claimed).extracting(EventMessage::id).containsExactly(id);
        assertThat(claimed.get(0).payloadJson()).contains("O-1");

        // A claimed-but-not-consumed message is not re-claimed within the abandoned window.
        assertThat(store.claim(CHANNEL, TOPIC, 10)).isEmpty();

        assertThat(store.consumed(CHANNEL, TOPIC, "O-1")).isFalse();
        store.markConsumed(id, CHANNEL, TOPIC, "O-1");
        assertThat(store.consumed(CHANNEL, TOPIC, "O-1")).isTrue();

        // A consumed message is never claimed again.
        assertThat(store.claim(CHANNEL, TOPIC, 10)).isEmpty();
    }

    @Test
    void deduplicatesARedeliveredIdempotencyKey() {
        // Two distinct messages carrying the same business key (a redelivery upstream).
        String first = store.publish(CHANNEL, TOPIC, "O-2", "{\"orderId\":\"O-2\"}", "user-admin");
        store.markConsumed(first, CHANNEL, TOPIC, "O-2");

        String second = store.publish(CHANNEL, TOPIC, "O-2", "{\"orderId\":\"O-2\"}",
                "user-admin");
        // The consumer sees the key is already consumed and skips the pipeline...
        assertThat(store.consumed(CHANNEL, TOPIC, "O-2")).isTrue();
        // ...then acknowledges the duplicate: marking it consumed again is a no-op, not an error.
        store.markConsumed(second, CHANNEL, TOPIC, "O-2");
        assertThat(store.claim(CHANNEL, TOPIC, 10)).isEmpty();
    }

    @Test
    void deadLettersAfterTheAttemptCeiling() {
        String id = store.publish(CHANNEL, TOPIC, "O-3", "{}", "user-admin");
        // Two attempts, ceiling of 2: the second failure dead-letters the message.
        store.markFailed(id, "boom", 2);
        store.markFailed(id, "boom again", 2);

        // A DEAD message is never claimed again, even past the abandoned window.
        assertThat(store.claim(CHANNEL, TOPIC, 10)).isEmpty();
    }

    /**
     * The read surface the operations console renders (docs/silent-tolerance.md O1): a dead-letter
     * is visible with its app, error, and attempt count — the store's javadoc promise, now a
     * queryable fact rather than an unread row.
     */
    @Test
    void deadLettersStayVisibleWithTheirAppAndError() {
        // Its own topic: the class shares one tql_event table, so the sibling tests' rows
        // must not be claimable — or counted — here.
        String topic = "orders.visible";
        String id = store.publish(CHANNEL, topic, "O-4", "{}", "user-admin");
        store.markFailed(id, "boom", 1);

        io.tesseraql.core.messaging.ChannelEvent dead = store.recent(50).stream()
                .filter(event -> event.id().equals(id))
                .findFirst().orElseThrow();
        assertThat(dead.status()).isEqualTo("DEAD");
        assertThat(dead.appName()).isEqualTo("user-admin");
        assertThat(dead.lastError()).isEqualTo("boom");
        assertThat(dead.attempts()).isEqualTo(1);
        assertThat(dead.channel()).isEqualTo(CHANNEL);
        assertThat(dead.topic()).isEqualTo(topic);
        assertThat(dead.publishedAt()).isNotNull();

        assertThat(store.countByStatus().getOrDefault("DEAD", 0)).isGreaterThanOrEqualTo(1);
        assertThat(store.find(id)).hasValueSatisfying(
                found -> assertThat(found.status()).isEqualTo("DEAD"));
    }

    /** An operator's redelivery flips DEAD back to PENDING and the next claim picks it up. */
    @Test
    void redeliverRequeuesADeadMessageForTheNextClaim() {
        String topic = "orders.redeliver";
        String id = store.publish(CHANNEL, topic, "O-5", "{}", "user-admin");
        store.markFailed(id, "boom", 1);
        assertThat(store.claim(CHANNEL, topic, 10)).isEmpty();

        assertThat(store.redeliver(id)).isTrue();

        // The claim is cleared with the status, so the requeued message is claimable now, not
        // after the abandoned window; the attempt count is kept so the history stays honest.
        List<EventMessage> claimed = store.claim(CHANNEL, topic, 10);
        assertThat(claimed).extracting(EventMessage::id).containsExactly(id);
        assertThat(claimed.get(0).attempts()).isEqualTo(1);
    }

    /** Redelivery only requeues dead letters: pending or consumed rows answer false. */
    @Test
    void redeliverRefusesANonDeadMessage() {
        String pending = store.publish(CHANNEL, "orders.refuse", "O-6", "{}", "user-admin");
        assertThat(store.redeliver(pending)).isFalse();
        assertThat(store.redeliver("no-such-id")).isFalse();
    }
}
