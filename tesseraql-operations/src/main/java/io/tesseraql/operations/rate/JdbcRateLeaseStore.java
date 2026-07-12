package io.tesseraql.operations.rate;

import io.tesseraql.core.rate.RateBudget;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/**
 * The main-database lease ledger behind cluster-scoped rate limits (docs/deployment.md,
 * "Cluster rate limits"): one row per (scope, second-window) tracks how much of the window's
 * budget has been granted, and {@link #claim} hands out tokens with plain atomic UPDATEs —
 * portable across every supported dialect, no vendor locking features. The table is owned by
 * {@code ensureSchema} (deliberately outside the Flyway component set, like the inbox); stale
 * windows are swept opportunistically on the claim path, so the ledger stays a handful of
 * rows without a scheduler.
 */
public final class JdbcRateLeaseStore implements RateBudget {

    /** Windows older than this are dead weight; swept opportunistically. */
    private static final long SWEEP_AFTER_SECONDS = 120;
    /** Sweep roughly every N claims — cheap, amortized, and clock-free. */
    private static final int SWEEP_EVERY = 256;

    private final DataSource dataSource;
    private final AtomicLong claims = new AtomicLong();

    public JdbcRateLeaseStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates {@code tql_rate_lease} when absent; tolerant of concurrent creation. */
    public void ensureSchema() {
        String ddl = "create table tql_rate_lease ("
                + "scope_key varchar(200) not null, "
                + "window_start bigint not null, "
                + "granted int not null, "
                + "primary key (scope_key, window_start))";
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException ex) {
            // already exists (concurrent boot or prior run) — verify instead of parsing vendor codes
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement probe = connection.prepareStatement(
                            "select count(*) from tql_rate_lease where 1 = 0")) {
                probe.executeQuery().close();
            } catch (SQLException verify) {
                throw new IllegalStateException(
                        "tql_rate_lease is unavailable: " + verify.getMessage(), verify);
            }
        }
    }

    @Override
    public int claim(String scopeKey, long windowStart, int want, int budget) {
        if (want <= 0 || budget <= 0) {
            return 0;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            int granted = tryClaim(connection, scopeKey, windowStart, want, budget);
            if (claims.incrementAndGet() % SWEEP_EVERY == 0) {
                sweep(connection, windowStart - SWEEP_AFTER_SECONDS);
            }
            return granted;
        } catch (SQLException ex) {
            throw new IllegalStateException("rate-lease claim failed: " + ex.getMessage(), ex);
        }
    }

    private int tryClaim(Connection connection, String scopeKey, long windowStart, int want,
            int budget) throws SQLException {
        // Fast path: the row exists and the full ask fits.
        if (update(connection, scopeKey, windowStart, want, budget) == 1) {
            return want;
        }
        // The window row may not exist yet — first claimer inserts it.
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into tql_rate_lease (scope_key, window_start, granted)"
                        + " values (?, ?, ?)")) {
            insert.setString(1, scopeKey);
            insert.setLong(2, windowStart);
            insert.setInt(3, Math.min(want, budget));
            insert.executeUpdate();
            return Math.min(want, budget);
        } catch (SQLException raced) {
            // Another node inserted concurrently — fall through to a partial claim.
        }
        // The row exists but the full ask does not fit: claim the remainder optimistically.
        try (PreparedStatement read = connection.prepareStatement(
                "select granted from tql_rate_lease where scope_key = ? and window_start = ?")) {
            read.setString(1, scopeKey);
            read.setLong(2, windowStart);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return 0; // swept or raced away; next window will serve
                }
                int seen = row.getInt(1);
                int remainder = Math.min(want, budget - seen);
                if (remainder <= 0) {
                    return 0;
                }
                try (PreparedStatement grab = connection.prepareStatement(
                        "update tql_rate_lease set granted = granted + ?"
                                + " where scope_key = ? and window_start = ? and granted = ?")) {
                    grab.setInt(1, remainder);
                    grab.setString(2, scopeKey);
                    grab.setLong(3, windowStart);
                    grab.setInt(4, seen);
                    return grab.executeUpdate() == 1 ? remainder : 0;
                }
            }
        }
    }

    private static int update(Connection connection, String scopeKey, long windowStart,
            int want, int budget) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update tql_rate_lease set granted = granted + ?"
                        + " where scope_key = ? and window_start = ? and granted <= ?")) {
            statement.setInt(1, want);
            statement.setString(2, scopeKey);
            statement.setLong(3, windowStart);
            statement.setInt(4, budget - want);
            return statement.executeUpdate();
        }
    }

    private static void sweep(Connection connection, long olderThan) {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from tql_rate_lease where window_start < ?")) {
            statement.setLong(1, olderThan);
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // best effort — the next sweep will catch up
        }
    }
}
