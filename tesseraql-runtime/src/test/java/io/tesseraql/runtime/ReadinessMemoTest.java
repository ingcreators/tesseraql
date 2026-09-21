package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.opsui.OpsDashboard;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The staleness rule counts from the last refresh attempt, never from a prober's silence
 * (docs/deployment-maturity.md decision 3, row 6).
 *
 * <p>Measured before this class existed: a member's readiness answered {@code DOWN} to the first
 * poll after four seconds of no polls, because nothing refreshes the memo but a poll and the rule
 * read three TTLs since the last completion as a failed refresh. A Kubernetes probe at the default
 * period of ten seconds would never have seen the pod ready. The variant that restores that rule
 * — counting from the held roll-up's age — is red on the first case.
 */
class ReadinessMemoTest {

    private static final long TTL = 1_000;

    /** A source whose age, status and blocking are the test's to set. */
    private static final class FakeSource implements ReadinessMemo.Source {
        final AtomicLong ageMillis = new AtomicLong();
        final AtomicReference<String> status = new AtomicReference<>("UP");
        final AtomicInteger computed = new AtomicInteger();
        volatile boolean present = true;
        volatile CountDownLatch release = new CountDownLatch(0);

        @Override
        public Optional<OpsDashboard.HeldHealth> held() {
            return present
                    ? Optional.of(new OpsDashboard.HeldHealth(
                            new OpsDashboard.HealthReport(status.get(), Map.of()), ageMillis.get()))
                    : Optional.empty();
        }

        @Override
        public String compute() {
            computed.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return status.get();
        }
    }

    @Test
    void aPollAfterAQuietSpellAnswersTheHeldStatusAndStartsARefresh() throws Exception {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(100_000);
        ReadinessMemo memo = new ReadinessMemo(source, TTL, clock::get);
        // Ten seconds since the last roll-up landed: nobody polled, nothing hung.
        source.ageMillis.set(10 * TTL);

        assertThat(memo.status(source.held().orElseThrow()))
                .as("the held status is the answer; the prober's own gap is not an outage")
                .isEqualTo("UP");
        awaitComputed(source, 1);
        assertThat(source.computed.get()).as("the poll started exactly one refresh").isEqualTo(1);
    }

    @Test
    void aPollInsideTheTtlNeitherRefreshesNorWaits() {
        FakeSource source = new FakeSource();
        ReadinessMemo memo = new ReadinessMemo(source, TTL, () -> 0L);
        source.ageMillis.set(TTL / 2);

        assertThat(memo.status(source.held().orElseThrow())).isEqualTo("UP");
        assertThat(source.computed.get()).as("a fresh memo costs no probe").isZero();
    }

    @Test
    void aRefreshThatHasNotLandedInThreeTtlsAnswersDownUntilItLands() throws Exception {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(100_000);
        ReadinessMemo memo = new ReadinessMemo(source, TTL, clock::get);
        source.ageMillis.set(2 * TTL);
        source.release = new CountDownLatch(1);

        assertThat(memo.status(source.held().orElseThrow()))
                .as("the refresh has just started; the held status still answers")
                .isEqualTo("UP");
        awaitComputed(source, 1);
        clock.addAndGet(ReadinessMemo.STALE_AFTER_TTLS * TTL - 1);
        assertThat(memo.status(source.held().orElseThrow()))
                .as("one millisecond short of the bound, the held status still answers")
                .isEqualTo("UP");
        clock.addAndGet(1);
        assertThat(memo.status(source.held().orElseThrow()))
                .as("a refresh in flight for three TTLs is the hung probe the rule exists for")
                .isEqualTo("DOWN");

        source.release.countDown();
        for (int i = 0; i < 100 && memo.refreshing(); i++) {
            Thread.sleep(10);
        }
        assertThat(memo.refreshing()).as("the refresh landed").isFalse();
        source.ageMillis.set(0);
        assertThat(memo.status(source.held().orElseThrow()))
                .as("once landed, the fresh roll-up answers").isEqualTo("UP");
    }

    @Test
    void theFirstPollBeforeAnyRollUpAnswersNothingAndStartsOne() throws Exception {
        FakeSource source = new FakeSource();
        source.present = false;
        ReadinessMemo memo = new ReadinessMemo(source, TTL, () -> 0L);

        assertThat(memo.poll()).as("nothing is held, so nothing is claimed").isEmpty();
        awaitComputed(source, 1);
    }

    @Test
    void aRollUpThatThrowsReadsAsDownNeverAsAnException() {
        ReadinessMemo memo = new ReadinessMemo(new ReadinessMemo.Source() {
            @Override
            public Optional<OpsDashboard.HeldHealth> held() {
                return Optional.empty();
            }

            @Override
            public String compute() {
                throw new IllegalStateException("the store is gone");
            }
        }, TTL, () -> 0L);

        assertThat(memo.rollUp()).isEqualTo("DOWN");
    }

    private static void awaitComputed(FakeSource source, int count) throws InterruptedException {
        for (int i = 0; i < 200 && source.computed.get() < count; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(source.computed.get()).isGreaterThanOrEqualTo(count);
    }
}
