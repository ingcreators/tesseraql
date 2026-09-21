package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * A close that never completes is abandoned inside the bound, so the stop's exit stays inside
 * the margin the platform's grace period leaves after the drain (docs/deployment-maturity.md
 * decision 4). The variant that waits for the close under the start timeout is red on the
 * first case by a minute.
 */
class BoundedCloseTest {

    @Test
    void aCloseThatNeverCompletesIsAbandonedInsideTheBound() {
        Duration bound = Duration.ofMillis(200);
        long start = System.nanoTime();

        boolean closed = BoundedClose.await(new CompletableFuture<Void>(), bound, "a stub");

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(closed).isFalse();
        assertThat(elapsedMillis).as("the wait ends at the bound, not at the close")
                .isGreaterThanOrEqualTo(bound.toMillis()).isLessThan(bound.toMillis() * 10);
    }

    @Test
    void aCloseThatCompletesIsReportedAsSuch() {
        assertThat(BoundedClose.await(CompletableFuture.completedFuture(null),
                Duration.ofMillis(200), "a stub")).isTrue();
    }

    @Test
    void aCloseThatFailsIsNotTheStopsToThrow() {
        assertThat(BoundedClose.await(CompletableFuture.failedFuture(new IllegalStateException()),
                Duration.ofMillis(200), "a stub")).isFalse();
    }

    @Test
    void theProductionBoundFitsTheMargin() {
        // The chart derives the grace period as the drain bound plus fifteen seconds. Four closes
        // sit in sequence on the gateway's stop path — the front server, the host's Vert.x, the
        // outbound client, the gateway's own Vert.x — and all four at the bound must still leave
        // the JVM its exit inside that margin.
        assertThat(BoundedClose.BOUND.multipliedBy(4)).isLessThan(Duration.ofSeconds(15));
    }
}
