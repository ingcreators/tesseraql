package io.tesseraql.runtime;

import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.opsui.OpsDashboard;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Periodically forwards operational alerts — threshold breaches from the dashboard (design ch.
 * 26.11) — to the configured notification channel (roadmap Phase 20), and says when they clear.
 * Enabled when {@code tesseraql.notifications.alerts.channel} is configured.
 *
 * <p>An alert code pages once while it stays raised, and {@code ops.alertCleared} follows once it
 * clears (docs/deployment-maturity.md decision 9): a page that never says "over" is a page an
 * operator learns to ignore. Where the dedup window lives depends on where the condition does.
 *
 * <ul>
 * <li>A <em>node-local</em> condition — an error rate, a saturated lane, a pinned thread, a poll
 * source, a calendar, this node's readiness, its pool, its refusals, its stop — pages per node,
 * with the node named in the payload, because it is that node's condition.
 * <li>A <em>database-wide</em> condition — dead-lettered outbox events, dead-lettered queue
 * events, the batch failure rate — is the same on every node that shares the database, and used
 * to page N times for one dead letter. It is claimed through the claim table scheduled firings
 * use ({@code tql_job_claim}, the {@link JobSlaSweeper} shape): one node wins the claim and
 * pages; the others see it raised and stay quiet, retrying the claim each tick. The node that
 * paged is the node that announces the clearing and releases the claim, so a re-raise pages
 * again. A node that dies holding a claim leaves it until the table's seven-day prune, after
 * which the next node to find the condition still raised pages once more.
 * </ul>
 *
 * <p>The in-memory half is per node: after a restart a still-raised node-local alert pages once
 * more, which errs on the side of not losing an alert.
 */
final class AlertNotifySweep {

    /** Where the events go: the runtime binds the outbox store, a test a list. */
    @FunctionalInterface
    interface Sink {
        void insert(OutboxEvent event);
    }

    /** The cluster-wide claim: the runtime binds {@link JobRepository}, a test one map. */
    interface Claims {
        boolean tryClaim(String key, Instant window);

        void release(String key, Instant window);
    }

    /**
     * The OPS numbers whose condition lives in the shared database rather than on this node: the
     * batch failure rate, dead-lettered outbox events, dead-lettered queue events. Numbers, not
     * code literals, so the error-code index does not read this sentence as their message.
     */
    static final Set<Integer> DATABASE_WIDE = Set.of(9004, 9006, 9008);

    /**
     * The claim row's second key. A firing is claimed per fire time; an alert is claimed per
     * condition, so the window is a constant and the row is {@code (<app>:alert:<code>, epoch)}.
     */
    static final Instant CLAIM_WINDOW = Instant.EPOCH;

    private static final System.Logger LOG = System
            .getLogger(AlertNotifySweep.class.getName());

    private final OpsDashboard dashboard;
    private final Sink sink;
    private final String channel;
    private final long periodMs;
    private final String appName;
    private final Claims claims;
    private final String node;
    /** The codes this node has paged for and not yet seen clear. */
    private final Set<String> notified = new HashSet<>();
    /** Of those, the database-wide codes whose claim this node holds. */
    private final Set<String> held = new HashSet<>();
    /** What each paged alert said, for the clearing event to repeat. */
    private final Map<String, OpsDashboard.Alert> paged = new HashMap<>();

    AlertNotifySweep(OpsDashboard dashboard, Sink sink, String channel, long periodMs,
            String appName, Claims claims, String node) {
        this.dashboard = dashboard;
        this.sink = sink;
        this.channel = channel;
        this.periodMs = periodMs;
        this.appName = appName;
        this.claims = claims;
        this.node = node;
    }

    /** The claim table scheduled firings and SLA alerts already use. */
    static Claims claims(JobRepository repository) {
        return new Claims() {
            @Override
            public boolean tryClaim(String key, Instant window) {
                return repository.tryClaimFiring(key, window);
            }

            @Override
            public void release(String key, Instant window) {
                repository.releaseFiring(key, window);
            }
        };
    }

    void schedule(Schedules schedules) {
        schedules.every("system.alerts.notifier", periodMs, this::sweep);
    }

    /**
     * One pass: pages what newly raised, announces what cleared. Also run once by a stop that cut
     * requests at its bound, before the pools close, so {@code TQL-OPS-9013} leaves the node.
     *
     * @return the events enqueued
     */
    synchronized int sweep() {
        Map<String, OpsDashboard.Alert> current = new LinkedHashMap<>();
        for (OpsDashboard.Alert alert : dashboard.alerts()) {
            current.putIfAbsent(alert.code(), alert);
        }
        int enqueued = 0;
        for (OpsDashboard.Alert alert : current.values()) {
            String code = alert.code();
            if (notified.contains(code)) {
                continue;
            }
            boolean databaseWide = claims != null && isDatabaseWide(code);
            if (databaseWide && !claims.tryClaim(claimKey(code), CLAIM_WINDOW)) {
                // Another node paged for this condition, and it announces the clearing too.
                continue;
            }
            try {
                sink.insert(event("ops.alert", alert, databaseWide));
                enqueued++;
                // Recorded only after a successful enqueue — a failed insert used to mark the
                // code anyway, so the alert was never re-sent while it stayed raised.
                notified.add(code);
                paged.put(code, alert);
                if (databaseWide) {
                    held.add(code);
                }
            } catch (RuntimeException ex) {
                // The claim is released, or a failed send would burn it and the condition
                // would never page from any node.
                if (databaseWide) {
                    releaseQuietly(code);
                }
                LOG.log(System.Logger.Level.WARNING,
                        "Failed to enqueue alert notification " + code
                                + "; will retry next tick",
                        ex);
            }
        }
        for (String code : new ArrayList<>(notified)) {
            if (current.containsKey(code)) {
                continue;
            }
            OpsDashboard.Alert last = paged.get(code);
            boolean databaseWide = held.contains(code);
            try {
                sink.insert(event("ops.alertCleared", last, databaseWide));
                enqueued++;
                notified.remove(code);
                paged.remove(code);
                if (databaseWide) {
                    held.remove(code);
                    releaseQuietly(code);
                }
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "Failed to enqueue alert clearing for " + code
                                + "; will retry next tick",
                        ex);
            }
        }
        return enqueued;
    }

    private String claimKey(String code) {
        return appName + ":alert:" + code;
    }

    /** By the code's spelling: the OPS codes are literals, with no {@code TqlDomain} constant. */
    private static boolean isDatabaseWide(String code) {
        String prefix = "TQL-OPS-";
        if (code == null || !code.startsWith(prefix)) {
            return false;
        }
        try {
            return DATABASE_WIDE.contains(Integer.parseInt(code.substring(prefix.length())));
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private void releaseQuietly(String code) {
        try {
            claims.release(claimKey(code), CLAIM_WINDOW);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to release the alert claim for " + code, ex);
        }
    }

    private OutboxEvent event(String kind, OpsDashboard.Alert alert, boolean databaseWide) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", alert.code());
        payload.put("severity", alert.severity());
        payload.put("message", alert.message());
        payload.put("node", node);
        payload.put("scope", databaseWide ? "database" : "node");
        return NotifyEvents.event(channel, kind, payload, appName);
    }
}
