package io.tesseraql.oidc;

import io.tesseraql.core.util.SqlScripts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Database-backed OIDC authorization-request state (roadmap Phase 25): when {@code /login} issues a
 * redirect it records the anti-CSRF {@code state} with its {@code nonce} and PKCE
 * {@code code_verifier}; the {@code /callback} consumes that row exactly once. An unknown,
 * already-consumed, or expired {@code state} is rejected — defeating CSRF, code-injection, and
 * replay — and the single-use claim holds across all nodes sharing the database.
 */
public final class OidcStateStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final DataSource dataSource;

    public OidcStateStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The nonce and PKCE verifier recorded against a pending {@code state}. */
    public record Pending(String nonce, String codeVerifier) {
    }

    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, OidcStateStore.class,
                    "/tesseraql/db/migration/oidc/V1__oidc_state.sql");
        } catch (SQLException ex) {
            throw new OidcException("Failed to create OIDC state schema: " + ex.getMessage(), ex);
        }
    }

    /** Records an issued authorization request; prunes expired state rows. */
    public void store(String state, String nonce, String codeVerifier) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_oidc_state where created_at < ?")) {
                prune.setTimestamp(1, Timestamp.from(Instant.now().minus(STATE_TTL)));
                prune.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_oidc_state (state, nonce, code_verifier, created_at)"
                            + " values (?, ?, ?, ?)")) {
                insert.setString(1, state);
                insert.setString(2, nonce);
                insert.setString(3, codeVerifier);
                insert.setTimestamp(4, Timestamp.from(Instant.now()));
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new OidcException("Failed to record OIDC state: " + ex.getMessage(), ex);
        }
    }

    /**
     * Consumes a pending {@code state} exactly once: its nonce and verifier when the state was
     * pending, empty when it is unknown, expired, or already consumed.
     */
    public Optional<Pending> consume(String state) {
        if (state == null) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            Pending pending;
            try (PreparedStatement select = connection.prepareStatement(
                    "select nonce, code_verifier from tql_oidc_state where state = ?"
                            + " and created_at >= ?")) {
                select.setString(1, state);
                select.setTimestamp(2, Timestamp.from(Instant.now().minus(STATE_TTL)));
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    pending = new Pending(rs.getString(1), rs.getString(2));
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "delete from tql_oidc_state where state = ?")) {
                delete.setString(1, state);
                // The delete is the single-use claim: a concurrent consumer loses it.
                if (delete.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            return Optional.of(pending);
        } catch (SQLException ex) {
            throw new OidcException("Failed to consume OIDC state: " + ex.getMessage(), ex);
        }
    }
}
