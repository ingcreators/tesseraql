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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for multi-node outbox claiming (design ch. 39.3): concurrent dispatchers never
 * claim the same event, and an abandoned SENDING claim becomes deliverable again.
 */
@Testcontainers
class OutboxClaimIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    JdbcOutboxStore store;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        store = new JdbcOutboxStore(dataSource);
        store.ensureSchema();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("delete from tql_outbox_event");
        }
    }

    /**
     * A scheduled entry waits for its instant (docs/notifications.md, "Scheduled delivery"):
     * the dispatcher already polls, so holding a row back is a filter on the claim, not a
     * second mover.
     */
    @Test
    void aScheduledEntryIsNotClaimedBeforeItsTime() throws Exception {
        Instant future = Instant.now().plusSeconds(3600);
        Instant past = Instant.now().minusSeconds(60);
        try (Connection connection = connect()) {
            store.insert(connection, scheduled("REMINDER", "later", future, null));
            store.insert(connection, scheduled("REMINDER", "due", past, null));
            store.insert(connection, event("REMINDER", "now"));
        }

        List<OutboxEvent> claimed = store.claimPending(10);

        assertThat(claimed).extracting(OutboxEvent::aggregateId)
                .containsExactlyInAnyOrder("due", "now");
    }

    /**
     * The withdrawal (docs/notifications.md, "Cancelling a scheduled entry"): undelivered
     * entries filed under a key are cancelled in the caller's transaction, and cancelled is
     * terminal — the dispatcher never claims them again.
     */
    @Test
    void aWithdrawnEntryIsNeverClaimedAgain() throws Exception {
        Instant future = Instant.now().plusSeconds(3600);
        try (Connection connection = connect()) {
            store.insert(connection, scheduled("REMINDER", "o-1", future, "order-1"));
            store.insert(connection, scheduled("REMINDER", "o-2", null, "order-2"));
            store.insert(connection, scheduled("REMINDER", "o-1-again", null, "order-1"));
        }

        try (Connection connection = connect()) {
            assertThat(store.withdraw(connection, "app", "order-1")).isEqualTo(2);
        }

        // order-2 remains deliverable; both order-1 entries are gone from the claim, the
        // scheduled one and the immediate one alike.
        assertThat(store.claimPending(10)).extracting(OutboxEvent::aggregateId)
                .containsExactly("o-2");
    }

    /** A withdrawal that names nothing withdraws nothing, and says so. */
    @Test
    void aWithdrawalOfAnUnknownKeyWithdrawsNothing() throws Exception {
        try (Connection connection = connect()) {
            store.insert(connection, scheduled("REMINDER", "o-3", null, "order-3"));
            assertThat(store.withdraw(connection, "app", "order-missing")).isZero();
            assertThat(store.withdraw(connection, "app", null)).isZero();
        }
        assertThat(store.claimPending(10)).extracting(OutboxEvent::aggregateId)
                .containsExactly("o-3");
    }

    /**
     * The in-flight race, closed: withdrawing while a dispatcher holds the row must stop every
     * attempt after the one already on the wire.
     *
     * <p>Leaving {@code SENDING} alone stopped nothing. The held delivery would fail,
     * {@code markFailed} would write {@code FAILED} straight over the cancellation, and the next
     * poll would deliver a reminder for an order cancelled days before — a withdrawn business
     * message going out, which at-least-once has nothing to say about.
     */
    @Test
    void withdrawingAnInFlightEntryStopsItsRetries() throws Exception {
        try (Connection connection = connect()) {
            store.insert(connection, scheduled("REMINDER", "o-5", null, "order-5"));
        }
        // A dispatcher claims it: the row is now SENDING and held.
        List<OutboxEvent> claimed = store.claimPending(10);
        assertThat(claimed).extracting(OutboxEvent::aggregateId).containsExactly("o-5");
        String eventId = claimed.get(0).id();

        try (Connection connection = connect()) {
            assertThat(store.withdraw(connection, "app", "order-5")).isEqualTo(1);
        }

        // The held delivery fails, as it was always free to do.
        store.markFailed(eventId, "connection reset");

        // The failure must not have resurrected the row.
        assertThat(status(eventId)).isEqualTo("CANCELLED");
        assertThat(store.claimPending(10)).isEmpty();
    }

    /** Exhausted attempts must not relabel a withdrawal as a delivery failure either. */
    @Test
    void deadLetteringDoesNotRelabelAWithdrawnEntry() throws Exception {
        try (Connection connection = connect()) {
            store.insert(connection, scheduled("REMINDER", "o-6", null, "order-6"));
        }
        String eventId = store.claimPending(10).get(0).id();
        try (Connection connection = connect()) {
            store.withdraw(connection, "app", "order-6");
        }

        store.markDead(eventId, "attempts exhausted");

        // An operator acts on the status, so it has to say the real reason.
        assertThat(status(eventId)).isEqualTo("CANCELLED");
    }

    /** A delivery that already succeeded is recorded as sent: it did send. */
    @Test
    void aWithdrawalDoesNotDenyADeliveryThatAlreadyHappened() throws Exception {
        try (Connection connection = connect()) {
            store.insert(connection, scheduled("REMINDER", "o-7", null, "order-7"));
        }
        String eventId = store.claimPending(10).get(0).id();
        try (Connection connection = connect()) {
            store.withdraw(connection, "app", "order-7");
        }

        store.markSent(eventId);

        assertThat(status(eventId)).isEqualTo("SENT");
    }

    /** One row's status, read straight from the table. */
    private static String status(String eventId) throws Exception {
        try (Connection connection = connect();
                java.sql.PreparedStatement ps = connection.prepareStatement(
                        "select status from tql_outbox_event where event_id = ?")) {
            ps.setString(1, eventId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** A rolled-back cancellation withdraws nothing: it rides the command's transaction. */
    @Test
    void aRolledBackWithdrawalLeavesTheEntryDeliverable() throws Exception {
        try (Connection connection = connect()) {
            store.insert(connection, scheduled("REMINDER", "o-4", null, "order-4"));
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            assertThat(store.withdraw(connection, "app", "order-4")).isEqualTo(1);
            connection.rollback();
        }
        assertThat(store.claimPending(10)).extracting(OutboxEvent::aggregateId)
                .containsExactly("o-4");
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
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "update tql_outbox_event set claimed_at = now() - interval '10 minutes'");
        }
        assertThat(store.claimPending(10)).hasSize(1);
    }

    @Test
    void claimsAreScopedToTheHostedApps() throws Exception {
        try (Connection connection = connect()) {
            store.insert(connection, event("USER_CREATED", "a1", "app-a"));
            store.insert(connection, event("USER_CREATED", "b1", "app-b"));
        }

        // A runtime hosting app-a claims only its own events - never app-b's.
        assertThat(store.claimPending(10, Set.of("app-a")))
                .extracting(OutboxEvent::aggregateId).containsExactly("a1");

        // App-b's event stays deliverable for the runtime that hosts app-b.
        assertThat(store.claimPending(10, Set.of("app-b")))
                .extracting(OutboxEvent::aggregateId).containsExactly("b1");
    }

    private static OutboxEvent event(String type, String aggregateId) {
        return event(type, aggregateId, "user-admin");
    }

    /** An event with a not-before instant, a withdrawal key, or both. */
    private static OutboxEvent scheduled(String type, String aggregateId, Instant notBefore,
            String cancelKey) {
        return OutboxEvent.toInsert("user", aggregateId, type, "{}", "app", notBefore,
                cancelKey);
    }

    private static OutboxEvent event(String type, String aggregateId, String appName) {
        return new OutboxEvent(null, "user", aggregateId, type, "{}", "PENDING", 0, null,
                Instant.now(), null, appName, null, null);
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
