package io.tesseraql.operations.credential;

import io.tesseraql.core.credential.TotpStore;
import io.tesseraql.core.sql.Transactions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The JDBC TOTP store (roadmap Phase 50 slice 3) over {@code tql_user_totp} and
 * {@code tql_totp_recovery}. The replay guard is one conditional UPDATE
 * ({@code last_used_step < ?}): whoever wins it accepted the code, everyone else - including a
 * racing replay of the same code - is refused. Confirming and removing span both tables, so
 * each is one {@link Transactions} bracket: the factor is never left enabled without its
 * recovery codes, nor disabled with them behind.
 */
public final class JdbcTotpStore implements TotpStore {

    private final DataSource dataSource;

    public JdbcTotpStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the enrollment tables if absent, from the bundled vendor-aware scripts. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcTotpStore.class,
                    "/tesseraql/db/migration/totp/V1__user_totp.sql");
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource, JdbcTotpStore.class,
                    "/tesseraql/db/migration/totp/V2__totp_recovery.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create TOTP schema", ex);
        }
    }

    @Override
    public Optional<Enrollment> enrollment(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select secret, confirmed_at, last_used_step, pending_recovery "
                                + "from tql_user_totp where tenant_id = ? and subject = ?")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Enrollment(rs.getString(1),
                        rs.getTimestamp(2) != null, rs.getLong(3), rs.getString(4)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read TOTP enrollment", ex);
        }
    }

    @Override
    public void beginEnrollment(String tenantId, String subject, String secret,
            String plainRecoveryCodes) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update tql_user_totp set secret = ?, confirmed_at = null, "
                            + "last_used_step = 0, created_at = ?, pending_recovery = ? "
                            + "where tenant_id = ? and subject = ?")) {
                update.setString(1, secret);
                update.setTimestamp(2, Timestamp.from(Instant.now()));
                update.setString(3, plainRecoveryCodes);
                update.setString(4, tenant(tenantId));
                update.setString(5, subject);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into tql_user_totp
                      (tenant_id, subject, secret, last_used_step, created_at, pending_recovery)
                    values (?, ?, ?, 0, ?, ?)
                    """)) {
                insert.setString(1, tenant(tenantId));
                insert.setString(2, subject);
                insert.setString(3, secret);
                insert.setTimestamp(4, Timestamp.from(Instant.now()));
                insert.setString(5, plainRecoveryCodes);
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to begin TOTP enrollment", ex);
        }
    }

    @Override
    public boolean confirmEnrollment(String tenantId, String subject,
            List<String> recoveryCodeHashes) {
        try (Connection connection = dataSource.getConnection()) {
            return Transactions.call(connection, "totp confirm", c -> {
                try (PreparedStatement confirm = c.prepareStatement(
                        "update tql_user_totp set confirmed_at = ?, pending_recovery = null "
                                + "where tenant_id = ? and subject = ? "
                                + "and confirmed_at is null")) {
                    confirm.setTimestamp(1, Timestamp.from(Instant.now()));
                    confirm.setString(2, tenant(tenantId));
                    confirm.setString(3, subject);
                    if (confirm.executeUpdate() == 0) {
                        return false;
                    }
                }
                replaceRecoveryCodes(c, tenantId, subject, recoveryCodeHashes);
                return true;
            });
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to confirm TOTP enrollment", ex);
        }
    }

    @Override
    public boolean remove(String tenantId, String subject) {
        try (Connection connection = dataSource.getConnection()) {
            return Transactions.call(connection, "totp remove", c -> {
                replaceRecoveryCodes(c, tenantId, subject, List.of());
                try (PreparedStatement ps = c.prepareStatement(
                        "delete from tql_user_totp where tenant_id = ? and subject = ?")) {
                    ps.setString(1, tenant(tenantId));
                    ps.setString(2, subject);
                    return ps.executeUpdate() > 0;
                }
            });
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

    /** The subject's recovery codes become exactly {@code codeHashes}, on the given connection. */
    private static void replaceRecoveryCodes(Connection connection, String tenantId,
            String subject, List<String> codeHashes) throws SQLException {
        try (PreparedStatement wipe = connection.prepareStatement(
                "delete from tql_totp_recovery where tenant_id = ? and subject = ?")) {
            wipe.setString(1, tenant(tenantId));
            wipe.setString(2, subject);
            wipe.executeUpdate();
        }
        if (codeHashes.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into tql_totp_recovery (tenant_id, subject, code_hash, created_at)"
                        + " values (?, ?, ?, ?)")) {
            for (String hash : codeHashes) {
                insert.setString(1, tenant(tenantId));
                insert.setString(2, subject);
                insert.setString(3, hash);
                insert.setTimestamp(4, Timestamp.from(Instant.now()));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override
    public boolean consumeRecoveryCode(String tenantId, String subject, String codeHash) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement consume = connection.prepareStatement(
                        "delete from tql_totp_recovery where tenant_id = ? and subject = ?"
                                + " and code_hash = ?")) {
            consume.setString(1, tenant(tenantId));
            consume.setString(2, subject);
            consume.setString(3, codeHash);
            // The DELETE is the single-use guarantee: only one caller wins the row.
            return consume.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to consume a recovery code", ex);
        }
    }
}
