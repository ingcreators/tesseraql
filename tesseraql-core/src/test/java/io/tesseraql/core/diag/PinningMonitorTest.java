package io.tesseraql.core.diag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PinningMonitorTest {

    @Test
    void countsAndRetainsRecentEventsMostRecentFirst() {
        PinningMonitor monitor = new PinningMonitor(2);
        monitor.record(new PinningEvent("carrier-1", 30, "A.a", 1));
        monitor.record(new PinningEvent("carrier-2", 40, "B.b", 2));
        monitor.record(new PinningEvent("carrier-3", 50, "C.c", 3));

        assertThat(monitor.count()).isEqualTo(3);
        assertThat(monitor.recent()).extracting(PinningEvent::carrierThread)
                .containsExactly("carrier-3", "carrier-2");
    }
}
