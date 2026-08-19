package io.tesseraql.oauth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The database-backed {@link OAuthStore} on the framework datasource: every replica shares one
 * set of codes, refresh chains, clients and consents, which is what makes single-use and reuse
 * detection multi-node properties rather than per-node ones. The tables ride the {@code
 * security} migration component (V5), migrated once by the host before any runtime starts; SQL
 * stays ANSI-portable, the same discipline as {@code JdbcSessionStore}.
 */
public final class JdbcOAuthStore implements OAuthStore {

    private final DataSource dataSource;

    public JdbcOAuthStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<RegisteredClient> findClient(String clientId) {
        String sql = "select client_id, secret_hash, redirect_uris, client_name, metadata_json,"
                + " registered_at, last_seen_at from tql_oauth_client where client_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, clientId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RegisteredClient(
                        rs.getString("client_id"),
                        rs.getString("secret_hash"),
                        splitUris(rs.getString("redirect_uris")),
                        rs.getString("client_name"),
                        rs.getString("metadata_json"),
                        instant(rs.getTimestamp("registered_at")),
                        instant(rs.getTimestamp("last_seen_at"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth client lookup failed", e);
        }
    }

    @Override
    public void saveClient(RegisteredClient client) {
        // Portable upsert: update-then-insert, the same shape as the other framework stores.
        String update = "update tql_oauth_client set secret_hash = ?, redirect_uris = ?,"
                + " client_name = ?, metadata_json = ?, registered_at = ?, last_seen_at = ?"
                + " where client_id = ?";
        String insert = "insert into tql_oauth_client (secret_hash, redirect_uris, client_name,"
                + " metadata_json, registered_at, last_seen_at, client_id)"
                + " values (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection()) {
            if (write(connection, update, client) == 0) {
                write(connection, insert, client);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth client save failed", e);
        }
    }

    private static int write(Connection connection, String sql, RegisteredClient client)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, client.secretHash());
            statement.setString(2, String.join("\n", client.redirectUris()));
            statement.setString(3, client.clientName());
            statement.setString(4, client.metadataJson());
            statement.setTimestamp(5, timestamp(client.registeredAt()));
            statement.setTimestamp(6, timestamp(client.lastSeenAt()));
            statement.setString(7, client.clientId());
            return statement.executeUpdate();
        }
    }

    @Override
    public void touchClient(String clientId, Instant lastSeenAt) {
        String sql = "update tql_oauth_client set last_seen_at = ? where client_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(sql)) {
            update.setTimestamp(1, timestamp(lastSeenAt));
            update.setString(2, clientId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth client touch failed", e);
        }
    }

    @Override
    public void saveCode(IssuedCode code) {
        String sql = "insert into tql_oauth_code (code_hash, client_id, subject, login_id,"
                + " resource_id, acting_role, code_challenge, redirect_uri, expires_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, code.codeHash());
            insert.setString(2, code.clientId());
            insert.setString(3, code.subject());
            insert.setString(4, code.loginId());
            insert.setString(5, code.resourceId());
            insert.setString(6, code.actingRole());
            insert.setString(7, code.codeChallenge());
            insert.setString(8, code.redirectUri());
            insert.setTimestamp(9, timestamp(code.expiresAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth code save failed", e);
        }
    }

    @Override
    public Optional<IssuedCode> consumeCode(String codeHash) {
        String select = "select code_hash, client_id, subject, login_id, resource_id,"
                + " acting_role, code_challenge, redirect_uri, expires_at from tql_oauth_code"
                + " where code_hash = ?";
        String delete = "delete from tql_oauth_code where code_hash = ?";
        try (Connection connection = dataSource.getConnection()) {
            IssuedCode code = null;
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setString(1, codeHash);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        code = new IssuedCode(
                                rs.getString("code_hash"),
                                rs.getString("client_id"),
                                rs.getString("subject"),
                                rs.getString("login_id"),
                                rs.getString("resource_id"),
                                rs.getString("acting_role"),
                                rs.getString("code_challenge"),
                                rs.getString("redirect_uri"),
                                instant(rs.getTimestamp("expires_at")));
                    }
                }
            }
            if (code == null) {
                return Optional.empty();
            }
            // The DELETE is the guard: of two concurrent consumers, exactly one deletes one
            // row, and the loser answers empty — single-use as a database property.
            try (PreparedStatement statement = connection.prepareStatement(delete)) {
                statement.setString(1, codeHash);
                if (statement.executeUpdate() == 0) {
                    return Optional.empty();
                }
            }
            return Optional.of(code);
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth code consume failed", e);
        }
    }

    @Override
    public void saveRefreshToken(IssuedRefreshToken token) {
        String sql = "insert into tql_oauth_refresh (token_hash, chain_id, client_id, subject,"
                + " login_id, resource_id, acting_role, issued_at, expires_at, rotated_at,"
                + " revoked_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, token.tokenHash());
            insert.setString(2, token.chainId());
            insert.setString(3, token.clientId());
            insert.setString(4, token.subject());
            insert.setString(5, token.loginId());
            insert.setString(6, token.resourceId());
            insert.setString(7, token.actingRole());
            insert.setTimestamp(8, timestamp(token.issuedAt()));
            insert.setTimestamp(9, timestamp(token.expiresAt()));
            insert.setTimestamp(10, timestamp(token.rotatedAt()));
            insert.setTimestamp(11, timestamp(token.revokedAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth refresh-token save failed", e);
        }
    }

    @Override
    public Optional<IssuedRefreshToken> findRefreshToken(String tokenHash) {
        String sql = "select token_hash, chain_id, client_id, subject, login_id, resource_id,"
                + " acting_role, issued_at, expires_at, rotated_at, revoked_at"
                + " from tql_oauth_refresh where token_hash = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, tokenHash);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth refresh-token lookup failed", e);
        }
    }

    @Override
    public boolean markRotated(String tokenHash, Instant rotatedAt) {
        // Guarded single-writer update: the WHERE clause is the race arbiter, so of two
        // concurrent rotations exactly one wins and the loser reports reuse.
        String sql = "update tql_oauth_refresh set rotated_at = ?"
                + " where token_hash = ? and rotated_at is null";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(sql)) {
            update.setTimestamp(1, timestamp(rotatedAt));
            update.setString(2, tokenHash);
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth refresh-token rotation failed", e);
        }
    }

    @Override
    public void revokeChain(String chainId, Instant revokedAt) {
        String sql = "update tql_oauth_refresh set revoked_at = ?"
                + " where chain_id = ? and revoked_at is null";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement update = connection.prepareStatement(sql)) {
            update.setTimestamp(1, timestamp(revokedAt));
            update.setString(2, chainId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth refresh-chain revoke failed", e);
        }
    }

    @Override
    public List<IssuedRefreshToken> refreshTokensFor(String clientId, String subject) {
        String sql = "select token_hash, chain_id, client_id, subject, login_id, resource_id,"
                + " acting_role, issued_at, expires_at, rotated_at, revoked_at"
                + " from tql_oauth_refresh where subject = ?"
                + (clientId == null ? "" : " and client_id = ?")
                + " and rotated_at is null and revoked_at is null order by issued_at";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, subject);
            if (clientId != null) {
                select.setString(2, clientId);
            }
            try (ResultSet rs = select.executeQuery()) {
                List<IssuedRefreshToken> tokens = new ArrayList<>();
                while (rs.next()) {
                    tokens.add(read(rs));
                }
                return tokens;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth refresh-token listing failed", e);
        }
    }

    @Override
    public void saveConsent(RecordedConsent consent) {
        String update = "update tql_oauth_consent set acting_role = ?, granted_at = ?"
                + " where client_id = ? and subject = ? and resource_id = ?";
        String insert = "insert into tql_oauth_consent (acting_role, granted_at, client_id,"
                + " subject, resource_id) values (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection()) {
            for (String sql : List.of(update, insert)) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, consent.actingRole());
                    statement.setTimestamp(2, timestamp(consent.grantedAt()));
                    statement.setString(3, consent.clientId());
                    statement.setString(4, consent.subject());
                    statement.setString(5, consent.resourceId());
                    if (statement.executeUpdate() > 0) {
                        return;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth consent save failed", e);
        }
    }

    @Override
    public Optional<RecordedConsent> findConsent(String clientId, String subject,
            String resourceId) {
        String sql = "select client_id, subject, resource_id, acting_role, granted_at"
                + " from tql_oauth_consent"
                + " where client_id = ? and subject = ? and resource_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, clientId);
            select.setString(2, subject);
            select.setString(3, resourceId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RecordedConsent(
                        rs.getString("client_id"),
                        rs.getString("subject"),
                        rs.getString("resource_id"),
                        rs.getString("acting_role"),
                        instant(rs.getTimestamp("granted_at"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth consent lookup failed", e);
        }
    }

    @Override
    public void deleteConsent(String clientId, String subject, String resourceId) {
        String sql = "delete from tql_oauth_consent"
                + " where client_id = ? and subject = ? and resource_id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement delete = connection.prepareStatement(sql)) {
            delete.setString(1, clientId);
            delete.setString(2, subject);
            delete.setString(3, resourceId);
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth consent delete failed", e);
        }
    }

    @Override
    public void deleteExpired(Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            for (String sql : List.of("delete from tql_oauth_code where expires_at < ?",
                    "delete from tql_oauth_refresh where expires_at < ?")) {
                try (PreparedStatement delete = connection.prepareStatement(sql)) {
                    delete.setTimestamp(1, timestamp(now));
                    delete.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth expiry prune failed", e);
        }
    }

    private static IssuedRefreshToken read(ResultSet rs) throws SQLException {
        return new IssuedRefreshToken(
                rs.getString("token_hash"),
                rs.getString("chain_id"),
                rs.getString("client_id"),
                rs.getString("subject"),
                rs.getString("login_id"),
                rs.getString("resource_id"),
                rs.getString("acting_role"),
                instant(rs.getTimestamp("issued_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("rotated_at")),
                instant(rs.getTimestamp("revoked_at")));
    }

    private static List<String> splitUris(String joined) {
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split("\n"));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
