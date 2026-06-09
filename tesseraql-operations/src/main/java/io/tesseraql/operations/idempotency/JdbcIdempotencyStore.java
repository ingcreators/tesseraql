package io.tesseraql.operations.idempotency;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.idempotency.IdempotencyStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link IdempotencyStore} persisting to {@code TQL_IDEMPOTENCY_RECORD}
 * (design ch. 39.4). Records expire after their TTL and are then reusable.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.IDEM, 5001);

    private final DataSource dataSource;

    public JdbcIdempotencyStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void ensureSchema() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists tql_idempotency_record (
                      scope varchar(256) not null,
                      idempotency_key varchar(512) not null,
                      request_hash varchar(128) not null,
                      status varchar(32) not null,
                      response_status integer,
                      response_body text,
                      response_content_type varchar(128),
                      expires_at timestamp not null,
                      created_at timestamp not null,
                      primary key (scope, idempotency_key)
                    )""");
        } catch (SQLException ex) {
            throw error("Failed to create idempotency schema", ex);
        }
    }

    @Override
    public BeginResult begin(String scope, String key, String requestHash, long ttlMillis) {
        try (Connection connection = dataSource.getConnection()) {
            Existing existing = find(connection, scope, key);
            Instant now = Instant.now();
            if (existing != null && existing.expiresAt.isAfter(now)) {
                return classify(existing, requestHash);
            }
            // No record, or expired: claim it as in progress.
            upsertInProgress(connection, scope, key, requestHash, now.plusMillis(ttlMillis), now);
            return new Proceed();
        } catch (SQLException ex) {
            throw error("Idempotency begin failed", ex);
        }
    }

    private BeginResult classify(Existing existing, String requestHash) {
        if (!existing.requestHash.equals(requestHash)) {
            return new Conflict("Idempotency key reused for a different request");
        }
        if ("COMPLETED".equals(existing.status)) {
            return new Replay(existing.responseStatus, existing.responseBody, existing.responseContentType);
        }
        return new Conflict("Request with this idempotency key is already in progress");
    }

    @Override
    public void complete(String scope, String key, int status, String body, String contentType) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        update tql_idempotency_record
                        set status = 'COMPLETED', response_status = ?, response_body = ?,
                            response_content_type = ?
                        where scope = ? and idempotency_key = ?""")) {
            ps.setInt(1, status);
            ps.setString(2, body);
            ps.setString(3, contentType);
            ps.setString(4, scope);
            ps.setString(5, key);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Idempotency complete failed", ex);
        }
    }

    private Existing find(Connection connection, String scope, String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "select * from tql_idempotency_record where scope = ? and idempotency_key = ?")) {
            ps.setString(1, scope);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Existing existing = new Existing();
                existing.requestHash = rs.getString("request_hash");
                existing.status = rs.getString("status");
                existing.responseStatus = rs.getInt("response_status");
                existing.responseBody = rs.getString("response_body");
                existing.responseContentType = rs.getString("response_content_type");
                existing.expiresAt = rs.getTimestamp("expires_at").toInstant();
                return existing;
            }
        }
    }

    private void upsertInProgress(Connection connection, String scope, String key,
            String requestHash, Instant expiresAt, Instant now) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "delete from tql_idempotency_record where scope = ? and idempotency_key = ?")) {
            delete.setString(1, scope);
            delete.setString(2, key);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into tql_idempotency_record
                  (scope, idempotency_key, request_hash, status, expires_at, created_at)
                values (?, ?, ?, 'IN_PROGRESS', ?, ?)""")) {
            insert.setString(1, scope);
            insert.setString(2, key);
            insert.setString(3, requestHash);
            insert.setTimestamp(4, Timestamp.from(expiresAt));
            insert.setTimestamp(5, Timestamp.from(now));
            insert.executeUpdate();
        }
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex).build();
    }

    private static final class Existing {
        String requestHash;
        String status;
        int responseStatus;
        String responseBody;
        String responseContentType;
        Instant expiresAt;
    }
}
