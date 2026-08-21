package io.tesseraql.operations.poll;

import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.SqlScripts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * Records which files a poll source has already consumed, so one file is imported once across
 * every replica (docs/audit-hardening.md Decision 4).
 *
 * <p>The read lock a poll source carries is a write-stability check, not inter-process exclusion:
 * a file is read once its fingerprint stops changing, which says nothing about whether another
 * replica is reading it too. That gap is what this store closes, and it was worse before the
 * connectors became the framework's own — the library's local strategy at least wrote an atomic
 * marker file, while its remote strategies took <b>no lock at all</b>, so three replicas polling
 * one drop directory each imported every file. Nothing else covered it: the job claim in
 * {@code tql_job_claim} is per <em>firing</em>, not per file.
 *
 * <p>The arbitration is the same shape as that claim — an insert whose primary key decides the
 * winner — which is why this class carries no locking of its own. The poll loop calls it through
 * its own {@code Claim} interface, one method wide, so this module stays free of runtime types.
 */
public final class JdbcPollConsumedStore {

    /** TQL-BATCH-5320: the poll-source consumption store could not record a file. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.BATCH, 5320);

    private final DataSource dataSource;
    private final Duration retention;

    public JdbcPollConsumedStore(DataSource dataSource, Duration retention) {
        this.dataSource = dataSource;
        this.retention = retention;
    }

    /** Creates {@code tql_poll_consumed} (per dialect) if it does not exist. */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcPollConsumedStore.class,
                    "/tesseraql/db/migration/poll/V1__poll_consumed.sql");
        } catch (SQLException ex) {
            throw new TqlException(STORE_ERROR,
                    "Failed to create poll consumption schema: " + ex.getMessage(), ex);
        }
    }

    /**
     * Claims {@code fileKey} for {@code sourceId}, returning false when another replica has it.
     *
     * <p>Claiming before the import rather than confirming after it is what makes this atomic: two
     * replicas can both pass a "have I seen this?" check and both import, and only the insert
     * settles it.
     */
    public boolean claim(String sourceId, String fileKey) {
        try (Connection connection = dataSource.getConnection()) {
            pruneExpired(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_poll_consumed (source_id, file_key, consumed_at)"
                            + " values (?, ?, ?)")) {
                insert.setString(1, sourceId);
                insert.setString(2, fileKey);
                insert.setTimestamp(3, Timestamp.from(Instant.now()));
                insert.executeUpdate();
                return true;
            }
        } catch (SQLException ex) {
            if (SqlErrors.isUniqueViolation(ex)) {
                return false;
            }
            throw new TqlException(STORE_ERROR,
                    "Failed to claim poll file " + fileKey + " for " + sourceId + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    /** Whether {@code fileKey} is already recorded for {@code sourceId}. */
    public boolean claimed(String sourceId, String fileKey) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(
                        "select 1 from tql_poll_consumed where source_id = ? and file_key = ?")) {
            select.setString(1, sourceId);
            select.setString(2, fileKey);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException ex) {
            throw new TqlException(STORE_ERROR,
                    "Failed to read poll consumption for " + sourceId + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Drops a claim, so the file can be consumed again.
     *
     * <p>Camel calls this when the exchange that took the claim failed, which is the behaviour
     * wanted: a file that failed to import is not silently swallowed by a claim nobody will
     * complete.
     */
    public boolean release(String sourceId, String fileKey) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement(
                        "delete from tql_poll_consumed where source_id = ? and file_key = ?")) {
            delete.setString(1, sourceId);
            delete.setString(2, fileKey);
            return delete.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new TqlException(STORE_ERROR,
                    "Failed to release poll file " + fileKey + " for " + sourceId + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    /** Drops every claim for {@code sourceId}. */
    public void clear(String sourceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement(
                        "delete from tql_poll_consumed where source_id = ?")) {
            delete.setString(1, sourceId);
            delete.executeUpdate();
        } catch (SQLException ex) {
            throw new TqlException(STORE_ERROR,
                    "Failed to clear poll consumption for " + sourceId + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Prunes claims older than the retention window.
     *
     * <p>The window is the memory: a partner re-sending a byte-identical file inside it is skipped,
     * and outside it is imported again. That is the user-visible half of this feature and the
     * reason the window is a declared key rather than a constant.
     */
    private void pruneExpired(Connection connection) throws SQLException {
        try (PreparedStatement prune = connection.prepareStatement(
                "delete from tql_poll_consumed where consumed_at < ?")) {
            prune.setTimestamp(1, Timestamp.from(Instant.now().minus(retention)));
            prune.executeUpdate();
        }
    }
}
