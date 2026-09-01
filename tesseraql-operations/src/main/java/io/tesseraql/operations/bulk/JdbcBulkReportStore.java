package io.tesseraql.operations.bulk;

import io.tesseraql.core.bulk.BulkReportStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The JDBC bulk-report store (docs/bulk-report.md decision 6): one row per stored report,
 * keyed by a random handle, scoped to the acting subject, expired by timestamp. Expired rows
 * are swept opportunistically on every write — the table's population is bounded by the
 * TTL times the bulk-action rate, so a scheduled sweeper would be ceremony.
 */
public final class JdbcBulkReportStore implements BulkReportStore {

    private final DataSource dataSource;

    public JdbcBulkReportStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Idempotent bootstrap, the movable-store recipe ({@code FrameworkMigrations}). */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcBulkReportStore.class,
                    "/tesseraql/db/migration/bulk-report/V1__bulk_report.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create bulk-report schema", ex);
        }
    }

    @Override
    public String put(String subject, String payload, long ttlMillis) {
        String handle = java.util.UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement sweep = connection.prepareStatement(
                    "delete from tql_bulk_report where expires_at < ?")) {
                sweep.setTimestamp(1, now);
                sweep.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_bulk_report (handle, subject, payload, expires_at)"
                            + " values (?, ?, ?, ?)")) {
                insert.setString(1, handle);
                insert.setString(2, subject);
                insert.setString(3, payload);
                insert.setTimestamp(4, Timestamp.from(Instant.now().plusMillis(ttlMillis)));
                insert.executeUpdate();
            }
            return handle;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to store bulk report", ex);
        }
    }

    @Override
    public Optional<String> find(String handle, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select payload from tql_bulk_report"
                                + " where handle = ? and subject = ? and expires_at >= ?")) {
            ps.setString(1, handle);
            ps.setString(2, subject);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read bulk report", ex);
        }
    }
}
