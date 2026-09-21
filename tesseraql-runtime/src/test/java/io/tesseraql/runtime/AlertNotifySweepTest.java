package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.core.telemetry.RingTracer;
import io.tesseraql.opsui.CalendarStatus;
import io.tesseraql.opsui.OpsDashboard;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Two nodes' sweeps over one dashboard state and one claim table (docs/deployment-maturity.md
 * decision 9): a database-wide code pages once across the cluster, a node-local code pages per
 * node with its node named, a cleared code is announced once by the node that paged it and may
 * page again, and a failed enqueue releases the claim. The variants — per-node dedup for every
 * code, no node in the payload, no clearing event — are each red on one case.
 */
class AlertNotifySweepTest {

    /** One claim table shared by every node under test: the row is the key, the first insert wins. */
    private static final class SharedClaims implements AlertNotifySweep.Claims {
        final Set<String> rows = ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryClaim(String key, Instant window) {
            return rows.add(key + "@" + window);
        }

        @Override
        public void release(String key, Instant window) {
            rows.remove(key + "@" + window);
        }
    }

    /** A node's outbox: what it enqueued, and a switch to make the insert fail. */
    private static final class Outbox implements AlertNotifySweep.Sink {
        final List<OutboxEvent> events = new ArrayList<>();
        volatile boolean down;

        @Override
        public void insert(OutboxEvent event) {
            if (down) {
                throw new IllegalStateException("the outbox's pool is gone");
            }
            events.add(event);
        }

        NotifyEvents.Envelope last() {
            return NotifyEvents.parse(events.get(events.size() - 1).payloadJson());
        }
    }

    /** The one dashboard both nodes read: dead letters (database-wide) and a calendar (node-local). */
    private final AtomicInteger deadLetters = new AtomicInteger();
    private final CalendarStatus calendars = new CalendarStatus();
    private final OpsDashboard dashboard = new OpsDashboard(null, null, null,
            new RingTracer(4), 200L)
            .outboxCounts(() -> Map.of("DEAD", deadLetters.get()))
            .calendars(calendars);
    private final SharedClaims claims = new SharedClaims();
    private final Outbox outboxA = new Outbox();
    private final Outbox outboxB = new Outbox();
    private final AlertNotifySweep nodeA = new AlertNotifySweep(dashboard, outboxA, "ops-mail",
            60_000L, "orders", claims, "node-a");
    private final AlertNotifySweep nodeB = new AlertNotifySweep(dashboard, outboxB, "ops-mail",
            60_000L, "orders", claims, "node-b");

    @Test
    void aDatabaseWideConditionPagesOnceAcrossTwoNodes() {
        deadLetters.set(2);

        assertThat(nodeA.sweep()).as("the first sweep to find the condition pages").isEqualTo(1);
        assertThat(nodeB.sweep()).as("the second node sees the claim and stays quiet").isZero();
        assertThat(nodeA.sweep()).as("still raised: no second page").isZero();
        assertThat(nodeB.sweep()).isZero();

        NotifyEvents.Envelope page = outboxA.last();
        assertThat(page.source()).isEqualTo("ops.alert");
        assertThat(page.channel()).isEqualTo("ops-mail");
        assertThat(page.payload()).containsEntry("code", "TQL-OPS-9006")
                .containsEntry("severity", "warning").containsEntry("node", "node-a")
                .containsEntry("scope", "database");
        assertThat(outboxB.events).isEmpty();
        assertThat(claims.rows).containsExactly("orders:alert:TQL-OPS-9006@1970-01-01T00:00:00Z");
    }

    @Test
    void aNodeLocalConditionPagesPerNodeNamingTheNode() {
        calendars.failedOpen("nightly", "jp-business-days", "no such calendar");

        assertThat(nodeA.sweep()).isEqualTo(1);
        assertThat(nodeB.sweep()).as("a node's own condition pages from every node").isEqualTo(1);

        assertThat(outboxA.last().payload()).containsEntry("code", "TQL-OPS-9009")
                .containsEntry("node", "node-a").containsEntry("scope", "node");
        assertThat(outboxB.last().payload()).containsEntry("code", "TQL-OPS-9009")
                .containsEntry("node", "node-b").containsEntry("scope", "node");
        assertThat(claims.rows).as("a node-local code takes no claim").isEmpty();
    }

    @Test
    void aClearedConditionIsAnnouncedOnceByTheNodeThatPagedAndMayPageAgain() {
        deadLetters.set(2);
        nodeA.sweep();
        nodeB.sweep();

        deadLetters.set(0);
        assertThat(nodeA.sweep()).as("the node that paged announces the clearing").isEqualTo(1);
        assertThat(nodeB.sweep()).as("the node that did not page says nothing").isZero();
        NotifyEvents.Envelope cleared = outboxA.last();
        assertThat(cleared.source()).isEqualTo("ops.alertCleared");
        assertThat(cleared.payload()).containsEntry("code", "TQL-OPS-9006")
                .containsEntry("node", "node-a").containsEntry("scope", "database");
        assertThat(claims.rows).as("the claim is released with the clearing").isEmpty();

        deadLetters.set(5);
        assertThat(nodeB.sweep()).as("a re-raise pages again, from whichever node finds it")
                .isEqualTo(1);
        assertThat(nodeA.sweep()).isZero();
        assertThat(outboxB.last().payload()).containsEntry("node", "node-b");

        deadLetters.set(0);
        assertThat(nodeA.sweep()).as("not this node's page to clear").isZero();
        assertThat(nodeB.sweep()).isEqualTo(1);
        assertThat(outboxB.last().source()).isEqualTo("ops.alertCleared");
    }

    @Test
    void aFailedEnqueueReleasesTheClaimAndRetriesNextTick() {
        deadLetters.set(1);
        outboxA.down = true;

        assertThat(nodeA.sweep()).isZero();
        assertThat(claims.rows).as("a claim whose page never left is released, or the condition"
                + " would never page from any node").isEmpty();

        outboxA.down = false;
        assertThat(nodeA.sweep()).as("retried on the next tick").isEqualTo(1);
        assertThat(nodeB.sweep()).isZero();
    }

    @Test
    void aStopThatCutRequestsPagesOnTheSweepTheStopRuns() {
        dashboard.stopCut(3, Duration.ofSeconds(45));

        assertThat(nodeA.sweep()).isEqualTo(1);
        assertThat(outboxA.last().payload()).containsEntry("code", "TQL-OPS-9013")
                .containsEntry("scope", "node").containsEntry("node", "node-a");
        assertThat(String.valueOf(outboxA.last().payload().get("message")))
                .contains("3 request(s)").contains("PT45S");
    }
}
