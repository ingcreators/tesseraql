package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.outbox.JdbcOutboxStore;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the ops dashboard (docs/boot-phases.md slice 3): the roll-up behind the console's
 * overview, health and alerts, with every probe the boot wires into it. Extracted verbatim from
 * {@code TesseraqlRuntime.start(...)}, together with the trace-log unwrap and the live
 * datasource probe only it uses.
 */
final class OpsDashboards {

    private static final Logger LOG = LoggerFactory.getLogger(OpsDashboards.class);

    private OpsDashboards() {
    }

    static io.tesseraql.opsui.OpsDashboard assemble(AppConfig config,
            JobRepository jobRepository, io.tesseraql.core.threading.ExecutionLanes lanes,
            io.tesseraql.core.diag.RingSqlExecutionLog slowSqlLog,
            io.tesseraql.core.telemetry.Tracer effectiveTracer,
            io.tesseraql.core.diag.PinningMonitor pinningMonitor, JdbcOutboxStore outboxStore,
            io.tesseraql.operations.messaging.JdbcEventChannelStore eventChannelStore,
            io.tesseraql.opsui.PollSourceStatus pollSourceStatus,
            io.tesseraql.opsui.CalendarStatus calendarStatus,
            javax.sql.DataSource dataSource, Map<String, HikariDataSource> dataSources) {
        return new io.tesseraql.opsui.OpsDashboard(jobRepository, lanes, slowSqlLog,
                traceLogOf(effectiveTracer),
                config.getString("tesseraql.diagnostics.slowSpanMillis")
                        .map(Long::parseLong).orElse(200L),
                new io.tesseraql.opsui.OpsDashboard.AlertThresholds(
                        config
                                .getString("tesseraql.diagnostics.errorRateWarnPercent")
                                .map(Double::parseDouble).orElse(5.0),
                        config.getString("tesseraql.diagnostics.slowRateWarnPercent")
                                .map(Double::parseDouble).orElse(20.0),
                        config
                                .getString("tesseraql.diagnostics.batchFailureWarnPercent")
                                .map(Double::parseDouble).orElse(10.0)),
                pinningMonitor)
                // Dead-lettered deliveries surface as an operational alert (Phase 20).
                .outboxCounts(outboxStore::countByStatus)
                // Dead-lettered queue messages alert the same way (silent-tolerance O1).
                .eventCounts(eventChannelStore::countByStatus)
                // A skipped or repeatedly failing poll source surfaces as an alert
                // instead of only a startup log line (docs/poll-source-status.md).
                .pollSources(pollSourceStatus)
                // A job firing unfiltered because its calendar would not resolve.
                .calendars(calendarStatus)
                // Truthful readiness (roadmap Phase 45): every configured datasource is
                // probed live; any failure rolls health up to DOWN so a load balancer
                // actually sheds traffic.
                .datasourceProbe(() -> probeDatasources(dataSource, dataSources))
                // An unauthenticated endpoint doing real work per poll is a lever; a memo
                // bounds it to one probe per TTL however fast the polls arrive
                // (docs/audit-hardening.md Decision 9).
                .healthTtl(io.tesseraql.core.util.Durations.parse(config
                        .getString("tesseraql.diagnostics.readinessTtl").orElse("1s")));
    }

    /**
     * The in-process span ring behind a tracer, wherever it sits.
     *
     * <p>An {@code instanceof} against the supplied tracer found nothing once OTLP wrapped it in a
     * composite, so the console's trace pages went empty in exactly the deployments carrying the
     * most telemetry. Asking the composite is the fix rather than reaching past it.
     */
    private static io.tesseraql.core.telemetry.TraceLog traceLogOf(
            io.tesseraql.core.telemetry.Tracer tracer) {
        if (tracer instanceof io.tesseraql.core.telemetry.TraceLog traceLog) {
            return traceLog;
        }
        if (tracer instanceof io.tesseraql.core.telemetry.CompositeTracer composite) {
            for (io.tesseraql.core.telemetry.Tracer delegate : composite.delegates()) {
                io.tesseraql.core.telemetry.TraceLog found = traceLogOf(delegate);
                if (found != null) {
                    return found;
                }
            }
        }
        return io.tesseraql.core.telemetry.TraceLog.empty();
    }

    /**
     * Live validity of every configured datasource (roadmap Phase 45): a short
     * {@link java.sql.Connection#isValid} round-trip per pool, by datasource name.
     */
    private static Map<String, Boolean> probeDatasources(javax.sql.DataSource main,
            Map<String, ? extends javax.sql.DataSource> named) {
        Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        out.put("main", datasourceValid(main));
        named.forEach((name, ds) -> {
            if (!"main".equals(name)) {
                out.put(name, datasourceValid(ds));
            }
        });
        return out;
    }

    private static boolean datasourceValid(javax.sql.DataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception ex) {
            // The health roll-up only sees false; without this the operator gets a DOWN with no
            // diagnosable cause (bad credentials, network, missing driver all look identical).
            LOG.warn("Datasource health probe failed", ex);
            return false;
        }
    }
}
