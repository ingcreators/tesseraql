package io.tesseraql.operations.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.outbox.OutboxStore;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dispatcher's retry/dead-letter policy (roadmap Phase 20): a failing event is marked failed
 * (and retried by later polls) until its attempts reach the ceiling, then dead-lettered.
 */
class OutboxDispatcherTest {

    /** An in-memory store recording the dispatcher's status transitions. */
    private static final class RecordingStore implements OutboxStore {

        final List<OutboxEvent> pending = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> dead = new ArrayList<>();

        @Override
        public String insert(Connection connection, OutboxEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OutboxEvent> listPending(int limit) {
            return List.copyOf(pending);
        }

        @Override
        public void markSent(String eventId) {
            sent.add(eventId);
        }

        @Override
        public void markFailed(String eventId, String error) {
            failed.add(eventId);
        }

        @Override
        public void markDead(String eventId, String error) {
            dead.add(eventId);
        }
    }

    private static OutboxEvent event(String id, int attempts) {
        return new OutboxEvent(id, "Notification", "r.n", "NOTIFICATION", "{}", "PENDING",
                attempts, null, Instant.now(), null, "app");
    }

    @Test
    void deliversAndMarksSent() {
        RecordingStore store = new RecordingStore();
        store.pending.add(event("e1", 0));

        int sent = new OutboxDispatcher(store, e -> {
        }).dispatch(10);

        assertThat(sent).isEqualTo(1);
        assertThat(store.sent).containsExactly("e1");
        assertThat(store.failed).isEmpty();
        assertThat(store.dead).isEmpty();
    }

    @Test
    void aFailureBelowTheCeilingMarksFailedForRetry() {
        RecordingStore store = new RecordingStore();
        store.pending.add(event("e1", 0));

        new OutboxDispatcher(store, e -> {
            throw new IllegalStateException("boom");
        }, java.util.Set.of(), 3).dispatch(10);

        assertThat(store.failed).containsExactly("e1");
        assertThat(store.dead).isEmpty();
    }

    @Test
    void theFinalFailedAttemptDeadLetters() {
        RecordingStore store = new RecordingStore();
        // Two attempts already failed; the ceiling is three, so this failure is the last.
        store.pending.add(event("e1", 2));

        new OutboxDispatcher(store, e -> {
            throw new IllegalStateException("boom");
        }, java.util.Set.of(), 3).dispatch(10);

        assertThat(store.failed).isEmpty();
        assertThat(store.dead).containsExactly("e1");
    }

    @Test
    void aFailureDoesNotStopTheBatch() {
        RecordingStore store = new RecordingStore();
        store.pending.add(event("bad", 0));
        store.pending.add(event("good", 0));

        int sent = new OutboxDispatcher(store, event -> {
            if ("bad".equals(event.id())) {
                throw new IllegalStateException("boom");
            }
        }).dispatch(10);

        assertThat(sent).isEqualTo(1);
        assertThat(store.sent).containsExactly("good");
        assertThat(store.failed).containsExactly("bad");
    }
}
