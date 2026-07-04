package io.tesseraql.operations.credential;

import io.tesseraql.core.credential.CredentialTokenStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The JDBC one-time token store (roadmap Phase 50) over {@code tql_credential_token}: the
 * row holds the SHA-256 of the token, never the token; single use is a check-and-set on
 * {@code used_at}; issuing prunes expired rows and refuses while a live token of the same
 * purpose exists for the login.
 */
public final class JdbcCredentialTokenStore implements CredentialTokenStore {

    private final DataSource dataSource;
    private final SecureRandom random = new SecureRandom();

    public JdbcCredentialTokenStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the token table if absent, from the bundled vendor-aware script. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcCredentialTokenStore.class,
                    "/tesseraql/db/migration/credential/V1__credential_tokens.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create credential token schema", ex);
        }
    }

    @Override
    public Optional<String> issue(String loginId, String purpose, Duration timeToLive) {
        Instant now = Instant.now();
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement prune = connection.prepareStatement(
                    "delete from tql_credential_token where expires_at < ?")) {
                prune.setTimestamp(1, Timestamp.from(now));
                prune.executeUpdate();
            }
            try (PreparedStatement live = connection.prepareStatement(
                    "select count(*) from tql_credential_token "
                            + "where login_id = ? and purpose = ? and used_at is null "
                            + "and expires_at >= ?")) {
                live.setString(1, loginId);
                live.setString(2, purpose);
                live.setTimestamp(3, Timestamp.from(now));
                try (ResultSet rs = live.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return Optional.empty();
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into tql_credential_token
                      (token_hash, login_id, purpose, expires_at, created_at)
                    values (?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, hash(rawToken));
                insert.setString(2, loginId);
                insert.setString(3, purpose);
                insert.setTimestamp(4, Timestamp.from(now.plus(timeToLive)));
                insert.setTimestamp(5, Timestamp.from(now));
                insert.executeUpdate();
            }
            return Optional.of(rawToken);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to issue credential token", ex);
        }
    }

    @Override
    public Optional<String> consume(String rawToken, String purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            // Check-and-set: only one consumer can flip used_at, so racing confirms
            // cannot both win.
            try (PreparedStatement use = connection.prepareStatement(
                    "update tql_credential_token set used_at = ? "
                            + "where token_hash = ? and purpose = ? and used_at is null "
                            + "and expires_at >= ?")) {
                use.setTimestamp(1, Timestamp.from(now));
                use.setString(2, hash(rawToken));
                use.setString(3, purpose);
                use.setTimestamp(4, Timestamp.from(now));
                if (use.executeUpdate() == 0) {
                    return Optional.empty();
                }
            }
            try (PreparedStatement who = connection.prepareStatement(
                    "select login_id from tql_credential_token where token_hash = ?")) {
                who.setString(1, hash(rawToken));
                try (ResultSet rs = who.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to consume credential token", ex);
        }
    }

    private static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
