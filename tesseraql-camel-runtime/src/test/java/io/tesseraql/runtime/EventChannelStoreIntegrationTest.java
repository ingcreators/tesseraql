package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.messaging.EventMessage;
import io.tesseraql.operations.messaging.JdbcEventChannelStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 27 store-level: the pg-notify durable event log claims with SKIP LOCKED (a claimed message
 * is not re-delivered), deduplicates by idempotency key, and dead-letters after the attempt ceiling.
 * The {@link PgNotifyListener} only decides <em>when</em> to drain; these are the durability
 * guarantees that make delivery at-least-once regardless of any wake signal.
 */
@Testcontainers
class EventChannelStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
        String id = store.publish(CHANNEL, TOPIC, "O-1", "{\"orderId\":\"O-1\"}");

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
        String first = store.publish(CHANNEL, TOPIC, "O-2", "{\"orderId\":\"O-2\"}");
        store.markConsumed(first, CHANNEL, TOPIC, "O-2");

        String second = store.publish(CHANNEL, TOPIC, "O-2", "{\"orderId\":\"O-2\"}");
        // The consumer sees the key is already consumed and skips the pipeline...
        assertThat(store.consumed(CHANNEL, TOPIC, "O-2")).isTrue();
        // ...then acknowledges the duplicate: marking it consumed again is a no-op, not an error.
        store.markConsumed(second, CHANNEL, TOPIC, "O-2");
        assertThat(store.claim(CHANNEL, TOPIC, 10)).isEmpty();
    }

    @Test
    void deadLettersAfterTheAttemptCeiling() {
        String id = store.publish(CHANNEL, TOPIC, "O-3", "{}");
        // Two attempts, ceiling of 2: the second failure dead-letters the message.
        store.markFailed(id, "boom", 2);
        store.markFailed(id, "boom again", 2);

        // A DEAD message is never claimed again, even past the abandoned window.
        assertThat(store.claim(CHANNEL, TOPIC, 10)).isEmpty();
    }
}
