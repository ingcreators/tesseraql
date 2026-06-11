package io.tesseraql.core.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JfrPinningSourceTest {

    @Test
    void capturesVirtualThreadPinning() throws Exception {
        // synchronized-based pinning was removed in JDK 24 (JEP 491); only assert where it pins.
        assumeTrue(Runtime.version().feature() < 24, "virtual threads no longer pin on synchronized");

        PinningMonitor monitor = new PinningMonitor(16);
        try (JfrPinningSource source = new JfrPinningSource(monitor, Duration.ofMillis(1))) {
            Object lock = new Object();
            Thread pinned = Thread.ofVirtual().start(() -> {
                synchronized (lock) {
                    try {
                        Thread.sleep(80); // blocking while pinned to the carrier
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            pinned.join();

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (monitor.count() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(monitor.count()).isPositive();
            assertThat(monitor.recent().get(0).durationMs()).isGreaterThanOrEqualTo(0);
        }
    }
}
