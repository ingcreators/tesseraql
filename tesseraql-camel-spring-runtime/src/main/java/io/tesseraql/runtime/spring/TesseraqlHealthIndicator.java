package io.tesseraql.runtime.spring;

import io.tesseraql.opsui.OpsDashboard;
import io.tesseraql.runtime.TesseraqlRuntime;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Bridges the TesseraQL operations health roll-up to Spring Boot Actuator (design ch. 19.1, 26.11).
 * Reports {@code UP} when there are no active alerts and a custom {@code WARN} status otherwise,
 * attaching the key metrics (trace error rate, lanes, pinning, alerts) as health details.
 */
public final class TesseraqlHealthIndicator implements HealthIndicator {

    private final TesseraqlRuntime runtime;

    public TesseraqlHealthIndicator(TesseraqlRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        OpsDashboard.HealthReport report = runtime.opsDashboard().health();
        Health.Builder builder = "UP".equals(report.status())
                ? Health.up() : Health.status(new Status(report.status()));
        return builder.withDetails(report.details()).build();
    }
}
