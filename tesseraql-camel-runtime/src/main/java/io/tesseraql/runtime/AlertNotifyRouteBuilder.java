package io.tesseraql.runtime;

import io.tesseraql.operations.outbox.JdbcOutboxStore;
import io.tesseraql.opsui.OpsDashboard;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.camel.builder.RouteBuilder;

/**
 * Periodically forwards newly raised operational alerts — threshold breaches from the dashboard
 * (design ch. 26.11) — to the configured notification channel (roadmap Phase 20). Enabled when
 * {@code tesseraql.notifications.alerts.channel} is configured.
 *
 * <p>Each alert code notifies once while it stays raised; when it clears and later re-raises, it
 * notifies again. The dedup window is per node and in memory: after a restart a still-raised
 * alert notifies once more, which errs on the side of not losing an alert.
 */
final class AlertNotifyRouteBuilder extends RouteBuilder {

    private final OpsDashboard dashboard;
    private final JdbcOutboxStore store;
    private final String channel;
    private final long periodMs;
    private final String appName;
    private final Set<String> notified = java.util.concurrent.ConcurrentHashMap.newKeySet();

    AlertNotifyRouteBuilder(OpsDashboard dashboard, JdbcOutboxStore store, String channel,
            long periodMs, String appName) {
        this.dashboard = dashboard;
        this.store = store;
        this.channel = channel;
        this.periodMs = periodMs;
        this.appName = appName;
    }

    @Override
    public void configure() {
        from("timer:tql-alert-notify?period=" + periodMs + "&delay=" + periodMs)
                .routeId("system.alerts.notifier")
                .process(exchange -> check());
    }

    private void check() {
        java.util.List<OpsDashboard.Alert> alerts = dashboard.alerts();
        Set<String> current = new java.util.HashSet<>();
        for (OpsDashboard.Alert alert : alerts) {
            current.add(alert.code());
            if (notified.add(alert.code())) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("code", alert.code());
                payload.put("severity", alert.severity());
                payload.put("message", alert.message());
                store.insert(NotifyEvents.event(channel, "ops.alert", payload, appName));
            }
        }
        // A cleared alert may notify again the next time it raises.
        notified.retainAll(current);
    }
}
