package io.tesseraql.core.diag;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a pinning sample says, against a real JFR stream on the supported baseline.
 *
 * <p>This class had one test, guarded by {@code assumeTrue(Runtime.version().feature() < 24)}, and
 * it has been a no-op since Java 25 became the baseline: JEP 491 removed {@code synchronized}
 * pinning, so the body it asserted no longer happens. Deleting the assumption alone does not give
 * a red test — it gives an error, because the old body records nothing and the assertion after it
 * indexes an empty ring. The shape JEP 491 left in place is a blocking class initializer, which
 * still emits {@code jdk.VirtualThreadPinned} with a duration and a full stack.
 *
 * <p>Behind the skip, all three payload fields were wrong: the pinned <em>virtual</em> thread was
 * reported as the carrier, the VM's own reporting frame as the pinning site, and JFR's dispatch
 * moment as the time.
 */
class JfrPinningSourceTest {

    /**
     * Pins its carrier by blocking in a class initializer — the shape JEP 491 left in place.
     * Touched from exactly one test, so its initialization is that test's event.
     */
    private static final class SlowInitializer {
        static {
            try {
                Thread.sleep(120);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        static void touch() {
        }
    }

    @Test
    @Timeout(60)
    void aPinningSampleNamesTheCarrierTheSiteAndWhenItHappened() throws Exception {
        PinningMonitor monitor = new PinningMonitor(16);
        try (JfrPinningSource _ = new JfrPinningSource(monitor, Duration.ofMillis(1))) {
            Thread pinned = Thread.ofVirtual().start(SlowInitializer::touch);
            pinned.join();
            // The pin is over by here, so it must have STARTED before this instant. JFR delivers
            // in flushed batches well after that, which is what makes this discriminating: a
            // dispatch-time stamp lands after the join, a start-time stamp before it.
            long joined = System.currentTimeMillis();

            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (monitor.count() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(monitor.count()).isPositive();

            // anySatisfy, not get(0): recent() is most-recent-first and surefire reuses one JVM
            // per module, so at a 1 ms threshold a pin from another test can land at the head.
            assertThat(monitor.recent()).anySatisfy(event -> {
                assertThat(event.carrierThread())
                        .as("the carrier, not the pinned virtual thread whose name is empty")
                        .startsWith("ForkJoinPool");
                assertThat(event.topFrame())
                        .as("the application frame the pinning came from, not"
                                + " VirtualThread.postPinnedEvent")
                        .contains("SlowInitializer");
                assertThat(event.durationMs()).isGreaterThanOrEqualTo(100);
                assertThat(event.atEpochMs())
                        .as("the pinning's own start time, not the moment JFR delivered it —"
                                + " the pin was over before the join returned")
                        .isLessThanOrEqualTo(joined);
            });
        }
    }
}
