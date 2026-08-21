package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.events.TopicBus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The one live-event hub behind {@code GET /_tesseraql/events} (docs/inbox.md "Live badge",
 * docs/realtime.md): every open stream holds one subscription over the signal keys its page
 * cares about — the subject's inbox key for the bell, plus a topic key per {@code refreshOn:}
 * topic — and mutations signal those keys. Signals coalesce per key per subscription (a
 * pending set, not a queue), and the registry is bounded two ways
 * (docs/contract-bugfixes.md track I): the per-subject cap evicts the subject's own oldest
 * stream (the victim is the same user opening one tab too many), while the global cap
 * <em>refuses</em> the new subscription with {@code TQL-RATE-5030} — a full registry must
 * not silently end someone else's live view. Both caps are configurable
 * ({@code tesseraql.live.maxPerSubject} / {@code maxTotal}).
 */
final class LiveStreams implements TopicBus {

    /** Await outcome: the fired signal key, or one of the markers below. */
    static final String IDLE = " idle";
    static final String CLOSED = " closed";

    /**
     * TQL-RATE-5030: the live-event registry is at its global capacity — the new stream is
     * refused (HTTP 503; the page still works, it just does not live-refresh until a reload
     * finds a free slot or the operator raises {@code tesseraql.live.maxTotal}).
     */
    private static final TqlErrorCode AT_CAPACITY = new TqlErrorCode(TqlDomain.RATE, 5030);

    static final int DEFAULT_MAX_PER_SUBJECT = 4;
    static final int DEFAULT_MAX_TOTAL = 256;

    private final int maxPerSubject;
    private final int maxTotal;

    LiveStreams() {
        this(DEFAULT_MAX_PER_SUBJECT, DEFAULT_MAX_TOTAL);
    }

    LiveStreams(int maxPerSubject, int maxTotal) {
        this.maxPerSubject = maxPerSubject;
        this.maxTotal = maxTotal;
    }

    /** The signal key of a tenant's live-view topic (docs/realtime.md). */
    static String topicKey(String tenantId, String topic) {
        return "topic|" + (tenantId == null ? "" : tenantId) + '|' + topic;
    }

    /** The signal key of a subject's inbox badge (docs/inbox.md). */
    static String inboxKey(String tenantId, String subject) {
        return "inbox|" + (tenantId == null ? "" : tenantId) + '|' + subject;
    }

    /** key → subscriptions; insertion-ordered so cap evictions drop the oldest. */
    private final Map<String, List<Subscription>> byKey = new LinkedHashMap<>();
    /** subject → subscriptions, for the per-subject cap. */
    private final Map<String, List<Subscription>> bySubject = new LinkedHashMap<>();
    private int total;

    /** One live stream's mailbox; closing unregisters it everywhere. */
    final class Subscription implements AutoCloseable {

        private final String subject;
        private final List<String> keys;
        private final LinkedHashSet<String> pending = new LinkedHashSet<>();
        private boolean closed;

        private Subscription(String subject, List<String> keys) {
            this.subject = subject;
            this.keys = keys;
        }

        /** The fired signal key, {@link #IDLE} on timeout, or {@link #CLOSED} on eviction. */
        synchronized String await(Duration wait) throws InterruptedException {
            long deadline = System.currentTimeMillis() + wait.toMillis();
            while (!closed && pending.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return IDLE;
                }
                wait(remaining);
            }
            if (closed) {
                return CLOSED;
            }
            var iterator = pending.iterator();
            String key = iterator.next();
            iterator.remove();
            return key;
        }

        private synchronized void signal(String key) {
            pending.add(key);
            notifyAll();
        }

        private synchronized void end() {
            closed = true;
            notifyAll();
        }

        @Override
        public void close() {
            unregister(this);
        }
    }

    /** Subscribes a stream of {@code subject} to the given signal keys. */
    synchronized Subscription subscribe(String subject, List<String> keys) {
        Subscription subscription = new Subscription(subject, List.copyOf(keys));
        // Evict first, and only then take the list reference. An eviction can empty this
        // subject's list, and unregister() drops an emptied list from the map — so a reference
        // captured beforehand would be detached from the registry, and the new subscription
        // added to it would be invisible to unregister() forever: byKey would keep signalling a
        // closed stream and `total` would never come back down.
        List<Subscription> existing = bySubject.get(subject);
        while (existing != null && existing.size() >= maxPerSubject) {
            evict(existing.get(0));
            existing = bySubject.get(subject);
        }
        // Checked after the per-subject eviction (which may just have freed a slot): a full
        // registry refuses the newcomer instead of ending another user's stream.
        if (total >= maxTotal) {
            throw TqlException.builder(AT_CAPACITY)
                    .message("Live-event registry is at its global capacity of " + maxTotal
                            + " streams (tesseraql.live.maxTotal)")
                    .build();
        }
        List<Subscription> subjectSubs = bySubject.computeIfAbsent(subject,
                s -> new ArrayList<>());
        subjectSubs.add(subscription);
        for (String key : subscription.keys) {
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(subscription);
        }
        total++;
        return subscription;
    }

    /** Signals every live subscription of the key; pending signals coalesce. */
    synchronized void signal(String key) {
        List<Subscription> subscriptions = byKey.get(key);
        if (subscriptions != null) {
            // Snapshot: signal() never mutates the registry, but stay robust to reentrancy.
            for (Subscription subscription : List.copyOf(subscriptions)) {
                subscription.signal(key);
            }
        }
    }

    /** A committed command's local topic broadcast (docs/realtime.md). */
    @Override
    public void emit(String tenantId, String topic) {
        signal(topicKey(tenantId, topic));
    }

    private synchronized void unregister(Subscription subscription) {
        List<Subscription> subjectSubs = bySubject.get(subscription.subject);
        if (subjectSubs == null || !subjectSubs.remove(subscription)) {
            return; // already unregistered (evicted)
        }
        if (subjectSubs.isEmpty()) {
            bySubject.remove(subscription.subject);
        }
        for (String key : subscription.keys) {
            List<Subscription> keySubs = byKey.get(key);
            if (keySubs != null && keySubs.remove(subscription) && keySubs.isEmpty()) {
                byKey.remove(key);
            }
        }
        total--;
    }

    private void evict(Subscription subscription) {
        unregister(subscription);
        subscription.end();
    }
}
