package io.tesseraql.operations.workflow;

import io.tesseraql.core.workflow.DelegationStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The JDBC absence-rule store (roadmap Phase 52) over {@code tql_workflow_delegation}: one
 * row per subject, portable update-then-insert upsert, outside the Flyway component set.
 */
public final class JdbcDelegationStore implements DelegationStore {

    private final DataSource dataSource;

    public JdbcDelegationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the delegation table if absent, from the bundled vendor-aware script. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcDelegationStore.class,
                    "/tesseraql/db/migration/delegation/V1__workflow_delegation.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create delegation schema", ex);
        }
    }

    @Override
    public Optional<Rule> rule(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select delegate_subject, starts_at, ends_at "
                                + "from tql_workflow_delegation "
                                + "where tenant_id = ? and subject = ?")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Rule(rs.getString(1),
                        rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read delegation rule", ex);
        }
    }

    @Override
    public void put(String tenantId, String subject, String delegateSubject, Instant startsAt,
            Instant endsAt) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update tql_workflow_delegation set delegate_subject = ?, starts_at = ?, "
                            + "ends_at = ?, created_at = ? "
                            + "where tenant_id = ? and subject = ?")) {
                update.setString(1, delegateSubject);
                update.setTimestamp(2, Timestamp.from(startsAt));
                update.setTimestamp(3, Timestamp.from(endsAt));
                update.setTimestamp(4, Timestamp.from(Instant.now()));
                update.setString(5, tenant(tenantId));
                update.setString(6, subject);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into tql_workflow_delegation
                      (tenant_id, subject, delegate_subject, starts_at, ends_at, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, tenant(tenantId));
                insert.setString(2, subject);
                insert.setString(3, delegateSubject);
                insert.setTimestamp(4, Timestamp.from(startsAt));
                insert.setTimestamp(5, Timestamp.from(endsAt));
                insert.setTimestamp(6, Timestamp.from(Instant.now()));
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to write delegation rule", ex);
        }
    }

    @Override
    public void clear(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "delete from tql_workflow_delegation "
                                + "where tenant_id = ? and subject = ?")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to clear delegation rule", ex);
        }
    }

    @Override
    public java.util.List<Entry> unexpired(String tenantId, Instant at, int limit) {
        java.util.List<Entry> entries = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select subject, delegate_subject, starts_at, ends_at "
                                + "from tql_workflow_delegation "
                                + "where tenant_id = ? and ends_at >= ? "
                                + "order by starts_at, subject")) {
            ps.setString(1, tenant(tenantId));
            ps.setTimestamp(2, Timestamp.from(at));
            ps.setMaxRows(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getString(1), rs.getString(2),
                            rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list delegation rules", ex);
        }
        return entries;
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? "" : tenantId;
    }
}
