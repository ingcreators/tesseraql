package io.tesseraql.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;

/**
 * What Camel knows about its own routes, contributed to the health roll-up as a signal
 * (docs/audit-hardening.md Decision 9).
 *
 * <p>{@code OpsDashboard} cannot compute this. A route that stopped, or a consumer that is no
 * longer consuming, is Camel's fact about itself — the dashboard sees spans and datasources, which
 * say nothing about whether the SFTP consumer is still running.
 *
 * <p><b>A signal and an alert source, never the 503 gate.</b> The obvious wiring — hand Camel's
 * {@code HealthCheckRegistry} to the readiness endpoint — produces a guaranteed boot-time blackout:
 * {@code DefaultHealthCheckRegistry}'s constructor sets {@code initialState} to {@code DOWN}, so a
 * perfectly healthy consumer that has not polled yet reports DOWN, on exactly the file and SFTP
 * sources this adoption is for. Route and consumer checks also duplicate each other.
 *
 * <p>So this reads {@link ServiceStatus} directly rather than adopting the check registry: the
 * status is the fact that was wanted, without the initial-state semantics that make the registry
 * unsafe to gate on. Nothing here can make readiness answer DOWN; a stopped route surfaces as an
 * alert an operator sees, and the traffic decision stays with the datasource probe.
 */
final class RouteHealthSignals {

    private RouteHealthSignals() {
    }

    /**
     * Routes that are not started, by id and status.
     *
     * <p>Empty is the healthy answer, and an empty map is what the roll-up omits, so a healthy
     * process does not carry a permanently-empty key in its health details.
     */
    static Map<String, String> stoppedRoutes(CamelContext context) {
        Map<String, String> stopped = new LinkedHashMap<>();
        if (context == null) {
            return stopped;
        }
        for (Route route : context.getRoutes()) {
            ServiceStatus status = context.getRouteController().getRouteStatus(route.getId());
            if (status != null && !status.isStarted()) {
                stopped.put(route.getId(), status.name());
            }
        }
        return stopped;
    }
}
