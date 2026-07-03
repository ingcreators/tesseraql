package io.tesseraql.runtime.spring;

import io.tesseraql.opsui.OpsDashboard;
import io.tesseraql.runtime.TesseraqlRuntime;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Bridges the TesseraQL operations health roll-up to Spring Boot Actuator (design ch. 19.1, 26.11).
 * Reports {@code UP} when there are no active alerts, a custom {@code WARN} status on active
 * alerts, and {@code DOWN} when the datasource probe fails (roadmap Phase 45) — attaching the
 * key metrics (trace error rate, lanes, pinning, alerts, datasource probes) as health details.
 */
public final class TesseraqlHealthIndicator implements HealthIndicator {

    private final TesseraqlRuntime runtime;

    public TesseraqlHealthIndicator(TesseraqlRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        OpsDashboard.HealthReport report = runtime.opsDashboard().health();
        Health.Builder builder = switch (report.status()) {
            case "UP" -> Health.up();
            case "DOWN" -> Health.down();
            default -> Health.status(new Status(report.status()));
        };
        return builder.withDetails(report.details()).build();
    }
}
