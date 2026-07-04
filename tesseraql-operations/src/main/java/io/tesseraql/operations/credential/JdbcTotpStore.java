package io.tesseraql.operations.credential;

import io.tesseraql.core.credential.TotpStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The JDBC TOTP store (roadmap Phase 50 slice 3) over {@code tql_user_totp}. The replay
 * guard is one conditional UPDATE ({@code last_used_step < ?}): whoever wins it accepted
 * the code, everyone else - including a racing replay of the same code - is refused.
 */
public final class JdbcTotpStore implements TotpStore {

    private final DataSource dataSource;

    public JdbcTotpStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the enrollment table if absent, from the bundled vendor-aware script. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcTotpStore.class,
                    "/tesseraql/db/migration/totp/V1__user_totp.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create TOTP schema", ex);
        }
    }

    @Override
    public Optional<Enrollment> enrollment(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select secret, confirmed_at, last_used_step from tql_user_totp "
                                + "where tenant_id = ? and subject = ?")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Enrollment(rs.getString(1),
                        rs.getTimestamp(2) != null, rs.getLong(3)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read TOTP enrollment", ex);
        }
    }

    @Override
    public void beginEnrollment(String tenantId, String subject, String secret) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update tql_user_totp set secret = ?, confirmed_at = null, "
                            + "last_used_step = 0, created_at = ? "
                            + "where tenant_id = ? and subject = ?")) {
                update.setString(1, secret);
                update.setTimestamp(2, Timestamp.from(Instant.now()));
                update.setString(3, tenant(tenantId));
                update.setString(4, subject);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into tql_user_totp
                      (tenant_id, subject, secret, last_used_step, created_at)
                    values (?, ?, ?, 0, ?)
                    """)) {
                insert.setString(1, tenant(tenantId));
                insert.setString(2, subject);
                insert.setString(3, secret);
                insert.setTimestamp(4, Timestamp.from(Instant.now()));
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to begin TOTP enrollment", ex);
        }
    }

    @Override
    public boolean confirmEnrollment(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_user_totp set confirmed_at = ? "
                                + "where tenant_id = ? and subject = ? "
                                + "and confirmed_at is null")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, tenant(tenantId));
            ps.setString(3, subject);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to confirm TOTP enrollment", ex);
        }
    }

    @Override
    public boolean remove(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "delete from tql_user_totp where tenant_id = ? and subject = ?")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to remove TOTP enrollment", ex);
        }
    }

    @Override
    public boolean markUsedStep(String tenantId, String subject, long step) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "update tql_user_totp set last_used_step = ? "
                                + "where tenant_id = ? and subject = ? "
                                + "and last_used_step < ?")) {
            ps.setLong(1, step);
            ps.setString(2, tenant(tenantId));
            ps.setString(3, subject);
            ps.setLong(4, step);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to record TOTP step", ex);
        }
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? "" : tenantId;
    }
}
