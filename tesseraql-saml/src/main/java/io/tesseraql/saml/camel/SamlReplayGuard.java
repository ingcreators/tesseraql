package io.tesseraql.saml.camel;

import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.util.SqlScripts;
import io.tesseraql.saml.SamlException;
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
 * Database-backed SAML replay protection (design ch. 10.14, 20): SP-initiated AuthnRequest ids
 * are stored when issued and consumed exactly once when the response's {@code InResponseTo}
 * comes back - an unknown or already-consumed id is rejected, and the stored RelayState pins the
 * round-tripped value against tampering. Consumed assertion ids stay cached until their
 * {@code NotOnOrAfter}, so a replayed assertion is rejected on any node sharing the database.
 */
final class SamlReplayGuard {

    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    private final DataSource dataSource;

    SamlReplayGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, SamlReplayGuard.class,
                    "/tesseraql/db/migration/saml/V1__saml_replay.sql");
        } catch (SQLException ex) {
            throw new SamlException("Failed to create SAML replay schema: " + ex.getMessage(), ex);
        }
    }

    /** Records an issued AuthnRequest id with its RelayState; prunes expired requests. */
    void storeRequest(String requestId, String relayState) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_saml_request where created_at < ?")) {
                prune.setTimestamp(1, Timestamp.from(Instant.now().minus(REQUEST_TTL)));
                prune.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_saml_request (request_id, relay_state, created_at)"
                            + " values (?, ?, ?)")) {
                insert.setString(1, requestId);
                insert.setString(2, relayState);
                insert.setTimestamp(3, Timestamp.from(Instant.now()));
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new SamlException("Failed to record AuthnRequest: " + ex.getMessage(), ex);
        }
    }

    /**
     * Consumes a pending request exactly once: the relay state recorded at issue time when the
     * id was pending, empty when it is unknown, expired or already consumed.
     */
    Optional<String> consumeRequest(String requestId) {
        try (Connection connection = dataSource.getConnection()) {
            String relayState = null;
            try (PreparedStatement select = connection.prepareStatement(
                    "select relay_state from tql_saml_request where request_id = ?"
                            + " and created_at >= ?")) {
                select.setString(1, requestId);
                select.setTimestamp(2, Timestamp.from(Instant.now().minus(REQUEST_TTL)));
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    relayState = rs.getString(1);
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "delete from tql_saml_request where request_id = ?")) {
                delete.setString(1, requestId);
                // The delete is the single-use claim: a concurrent consumer loses it.
                if (delete.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            return Optional.of(relayState == null ? "" : relayState);
        } catch (SQLException ex) {
            throw new SamlException("Failed to consume AuthnRequest: " + ex.getMessage(), ex);
        }
    }

    /** Marks an assertion consumed until {@code expiresAt}; false when it was already seen. */
    boolean markAssertionSeen(String assertionId, Instant expiresAt) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_saml_seen_assertion where expires_at < ?")) {
                prune.setTimestamp(1, Timestamp.from(Instant.now()));
                prune.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_saml_seen_assertion (assertion_id, expires_at)"
                            + " values (?, ?)")) {
                insert.setString(1, assertionId);
                insert.setTimestamp(2, Timestamp.from(expiresAt));
                insert.executeUpdate();
                return true;
            }
        } catch (SQLException ex) {
            if (SqlErrors.isUniqueViolation(ex)) {
                return false;
            }
            throw new SamlException("Failed to record assertion: " + ex.getMessage(), ex);
        }
    }
}
