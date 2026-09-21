package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The linger before the front closes (docs/deployment-maturity.md decision 10): a count that
 * reaches zero is not yet quiet — a keep-alive client's next request may be on the wire — so the
 * wait goes on for a whole linger and ends only when the count is still zero after it. A count
 * that rises during the linger starts the wait over; a bound that runs out ends it with the
 * requests still in flight; an interrupt ends it at once.
 */
class QuietDrainTest {

    /** A scripted count and a clock that advances by what was slept; the sleeps recorded. */
    private static final class Script {
        final Iterator<Integer> counts;
        final List<Long> sleeps = new ArrayList<>();
        long now;
        int last;

        Script(Integer... counts) {
            this.counts = List.of(counts).iterator();
        }

        int inFlight() {
            if (counts.hasNext()) {
                last = counts.next();
            }
            return last;
        }

        boolean sleep(long millis) {
            sleeps.add(millis);
            now += millis * 1_000_000L;
            return true;
        }

        long nanoTime() {
            return now;
        }
    }

    @Test
    void zeroIsQuietOnlyAfterAWholeLinger() {
        Script script = new Script(0, 0);
        boolean quiet = QuietDrain.await(script::inFlight, Duration.ofSeconds(20),
                script::sleep, script::nanoTime);
        assertThat(quiet).isTrue();
        assertThat(script.sleeps).as("one linger, then still zero")
                .containsExactly(QuietDrain.LINGER_MILLIS);
    }

    @Test
    void aRequestArrivingDuringTheLingerStartsTheWaitOver() {
        // In flight, zero, a request on the wire arrives during the linger and is in flight
        // at the next read, then zero for a whole linger: the close waits for the second
        // quiet, not the first zero.
        Script script = new Script(1, 0, 1, 1, 0, 0);
        boolean quiet = QuietDrain.await(script::inFlight, Duration.ofSeconds(20),
                script::sleep, script::nanoTime);
        assertThat(quiet).isTrue();
        assertThat(script.sleeps).containsExactly(QuietDrain.POLL_MILLIS,
                QuietDrain.LINGER_MILLIS, QuietDrain.POLL_MILLIS, QuietDrain.LINGER_MILLIS);
    }

    @Test
    void theBoundEndsTheWaitWithRequestsStillInFlight() {
        Script script = new Script(3);
        boolean quiet = QuietDrain.await(script::inFlight, Duration.ofMillis(120),
                script::sleep, script::nanoTime);
        assertThat(quiet).isFalse();
        assertThat(script.sleeps).as("polled until the bound ran out")
                .containsExactly(QuietDrain.POLL_MILLIS, QuietDrain.POLL_MILLIS,
                        QuietDrain.POLL_MILLIS);
    }

    @Test
    void anInterruptEndsTheWaitAtOnce() {
        Script script = new Script(2, 2);
        boolean quiet = QuietDrain.await(script::inFlight, Duration.ofSeconds(20),
                millis -> false, script::nanoTime);
        assertThat(quiet).isFalse();
    }
}
