package io.tesseraql.operations.audit;

import io.tesseraql.core.audit.RouteAuditSink;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;

/**
 * The JDBC business-route audit log (roadmap Phase 45): one row per audited invocation in
 * {@code tql_route_audit}, stamped with the owning app so the ops read surface narrows to the
 * caller's {@code ops.app.<name>} grants like every other per-app ops table. A failed insert
 * never fails the request — the audit log observes traffic, it does not gate it.
 */
public final class JdbcRouteAuditStore implements RouteAuditSink {

    private static final System.Logger LOG = System.getLogger(JdbcRouteAuditStore.class.getName());

    private final DataSource dataSource;

    public JdbcRouteAuditStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the audit table if absent, from the bundled vendor-aware migration script. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcRouteAuditStore.class,
                    "/tesseraql/db/migration/audit/V1__route_audit.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create route audit schema", ex);
        }
    }

    @Override
    public void record(RouteAuditEvent event) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        insert into tql_route_audit
                          (audit_id, app_name, route_id, http_method, url_path, actor,
                           tenant_id, status, duration_ms, params_json, trace_id, occurred_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, event.appName());
            ps.setString(3, event.routeId());
            ps.setString(4, event.httpMethod());
            ps.setString(5, event.urlPath());
            ps.setString(6, event.actor());
            ps.setString(7, event.tenantId());
            if (event.status() == null) {
                ps.setObject(8, null);
            } else {
                ps.setInt(8, event.status());
            }
            ps.setLong(9, event.durationMillis());
            ps.setString(10, event.paramsJson());
            ps.setString(11, event.traceId());
            ps.setTimestamp(12, Timestamp.from(event.occurredAt()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            LOG.log(System.Logger.Level.WARNING,
                    "Route audit insert failed (the request itself is unaffected): {0}",
                    ex.getMessage());
        }
    }

    /** The newest audit rows, scoped to the apps the caller may see (ops.app.* grants). */
    public List<Map<String, Object>> recent(int limit, Predicate<String> appFilter) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        select audit_id, app_name, route_id, http_method, url_path, actor,
                               tenant_id, status, duration_ms, params_json, trace_id,
                               occurred_at
                        from tql_route_audit
                        order by occurred_at desc
                        """)) {
            ps.setMaxRows(Math.max(limit * 4, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    String appName = rs.getString("app_name");
                    if (!appFilter.test(appName)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("auditId", rs.getString("audit_id"));
                    row.put("app", appName);
                    row.put("routeId", rs.getString("route_id"));
                    row.put("method", rs.getString("http_method"));
                    row.put("path", rs.getString("url_path"));
                    row.put("actor", rs.getString("actor"));
                    row.put("tenantId", rs.getString("tenant_id"));
                    row.put("status", rs.getObject("status"));
                    row.put("durationMs", rs.getLong("duration_ms"));
                    row.put("params", rs.getString("params_json"));
                    row.put("traceId", rs.getString("trace_id"));
                    row.put("occurredAt", String.valueOf(rs.getTimestamp("occurred_at")));
                    out.add(row);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Route audit query failed: " + ex.getMessage(), ex);
        }
        return out;
    }
}
