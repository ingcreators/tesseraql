package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.operations.outbox.JdbcOutboxStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for multi-node outbox claiming (design ch. 39.3): concurrent dispatchers never
 * claim the same event, and an abandoned SENDING claim becomes deliverable again.
 */
@Testcontainers
class OutboxClaimIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    JdbcOutboxStore store;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        store = new JdbcOutboxStore(dataSource);
        store.ensureSchema();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("delete from tql_outbox_event");
        }
    }

    @Test
    void concurrentClaimsNeverOverlap() throws Exception {
        try (Connection connection = connect()) {
            for (int i = 0; i < 20; i++) {
                store.insert(connection, event("USER_CREATED", "u" + i));
            }
        }

        // Four "nodes" claim five events each, concurrently.
        List<Callable<List<OutboxEvent>>> nodes = List.of(
                () -> store.claimPending(5), () -> store.claimPending(5),
                () -> store.claimPending(5), () -> store.claimPending(5));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        Set<String> claimed = new HashSet<>();
        int total = 0;
        try {
            for (Future<List<OutboxEvent>> result : pool.invokeAll(nodes)) {
                for (OutboxEvent event : result.get()) {
                    assertThat(claimed.add(event.id()))
                            .as("event %s claimed twice", event.id()).isTrue();
                    total++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(total).isEqualTo(20);

        // Everything is now claimed (SENDING); a further claim finds nothing.
        assertThat(store.claimPending(10)).isEmpty();
    }

    @Test
    void abandonedClaimsBecomeDeliverableAgain() throws Exception {
        try (Connection connection = connect()) {
            store.insert(connection, event("USER_CREATED", "crash"));
        }
        assertThat(store.claimPending(10)).hasSize(1);
        assertThat(store.claimPending(10)).isEmpty();

        // Simulate a dispatcher that crashed mid-delivery: its claim ages past the timeout.
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(
                    "update tql_outbox_event set claimed_at = now() - interval '10 minutes'");
        }
        assertThat(store.claimPending(10)).hasSize(1);
    }

    private static OutboxEvent event(String type, String aggregateId) {
        return new OutboxEvent(null, "user", aggregateId, type, "{}", "PENDING", 0, Instant.now());
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
