package io.tesseraql.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * The live-badge subscriber registry (docs/inbox.md, "Live badge"): every open
 * {@code /_tesseraql/events} stream holds one subscription, and inbox mutations signal the
 * subject's subscriptions through {@link NotifyingInboxStore}. Signals coalesce (a
 * capacity-1 queue), and the registry is bounded both per subject and globally — a new
 * subscription evicts the oldest one over either cap, which simply ends that stream (the
 * browser's EventSource reconnects).
 */
final class InboxStreams {

    /** One await outcome. */
    enum Signal {
        /** The subject's unread count may have changed — emit a fresh badge. */
        CHANGED,
        /** Nothing happened within the wait — the caller's heartbeat tick. */
        IDLE,
        /** The subscription was evicted — end the stream. */
        CLOSED
    }

    private static final Object CHANGED = new Object();
    private static final Object CLOSED = new Object();
    private static final int MAX_PER_SUBJECT = 2;
    private static final int MAX_TOTAL = 256;

    /** Insertion-ordered so cap evictions drop the oldest subscription first. */
    private final Map<String, List<ArrayBlockingQueue<Object>>> subscribers = new LinkedHashMap<>();

    /** One live stream's mailbox; closing unregisters it. */
    final class Subscription implements AutoCloseable {

        private final String key;
        private final ArrayBlockingQueue<Object> queue;

        private Subscription(String key, ArrayBlockingQueue<Object> queue) {
            this.key = key;
            this.queue = queue;
        }

        Signal await(Duration wait) throws InterruptedException {
            Object token = queue.poll(wait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (token == null) {
                return Signal.IDLE;
            }
            return token == CLOSED ? Signal.CLOSED : Signal.CHANGED;
        }

        @Override
        public void close() {
            synchronized (InboxStreams.this) {
                List<ArrayBlockingQueue<Object>> queues = subscribers.get(key);
                if (queues != null && queues.remove(queue) && queues.isEmpty()) {
                    subscribers.remove(key);
                }
            }
        }
    }

    synchronized Subscription subscribe(String tenantId, String subject) {
        String key = key(tenantId, subject);
        List<ArrayBlockingQueue<Object>> queues = subscribers.computeIfAbsent(key,
                k -> new ArrayList<>());
        while (queues.size() >= MAX_PER_SUBJECT) {
            queues.remove(0).offer(CLOSED);
        }
        while (total() >= MAX_TOTAL) {
            evictOldest();
        }
        ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        queues.add(queue);
        return new Subscription(key, queue);
    }

    /** Signals every live stream of the subject; a pending signal coalesces. */
    synchronized void changed(String tenantId, String subject) {
        List<ArrayBlockingQueue<Object>> queues = subscribers.get(key(tenantId, subject));
        if (queues != null) {
            queues.forEach(queue -> queue.offer(CHANGED));
        }
    }

    private int total() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    private void evictOldest() {
        var iterator = subscribers.entrySet().iterator();
        if (iterator.hasNext()) {
            var eldest = iterator.next();
            eldest.getValue().remove(0).offer(CLOSED);
            if (eldest.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static String key(String tenantId, String subject) {
        return (tenantId == null ? "" : tenantId) + "|" + subject;
    }
}
