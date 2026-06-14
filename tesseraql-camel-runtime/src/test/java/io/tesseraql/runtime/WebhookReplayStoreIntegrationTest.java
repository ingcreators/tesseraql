package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.webhook.JdbcWebhookReplayStore;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 26: the JDBC webhook replay store records a delivery id once, rejects it on replay until
 * it expires, and prunes expired ids so a tolerance-aged id is accepted again.
 */
@Testcontainers
class WebhookReplayStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcWebhookReplayStore store;

    @BeforeAll
    static void setUp() {
        store = new JdbcWebhookReplayStore(dataSource());
        store.ensureSchema();
    }

    @Test
    void recordsOnceAndRejectsAValidReplay() {
        Instant valid = Instant.now().plus(Duration.ofMinutes(5));

        assertThat(store.markSeen("delivery-1", valid)).isTrue();
        // The same delivery within the tolerance window is a replay.
        assertThat(store.markSeen("delivery-1", valid)).isFalse();
    }

    @Test
    void acceptsAgainOnceTheEntryHasExpired() {
        Instant alreadyExpired = Instant.now().minus(Duration.ofSeconds(1));

        // An expired entry is pruned before the next insert, so the id is not treated as a replay.
        assertThat(store.markSeen("delivery-2", alreadyExpired)).isTrue();
        assertThat(store.markSeen("delivery-2", Instant.now().plus(Duration.ofMinutes(5))))
                .isTrue();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
