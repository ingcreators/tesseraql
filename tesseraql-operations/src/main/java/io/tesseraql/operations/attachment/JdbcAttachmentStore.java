package io.tesseraql.operations.attachment;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.SqlScripts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link AttachmentStore} (roadmap Phase 30 slice 1): the managed {@code tql_attachment}
 * metadata table. Each row ties a durable blob (by {@code storage_key}) to an owning business record
 * ({@code entity} + {@code entity_id}). Slice 1 records {@code scan_status = clean}; the scan-hook
 * (slice 3) is what flips it.
 */
public final class JdbcAttachmentStore implements AttachmentStore {

    /** TQL-LD-2845: the attachment store could not complete a JDBC operation. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.LD, 2845);

    private static final String COLUMNS = "attachment_id, entity, entity_id, filename, "
            + "content_type, byte_size, checksum, storage_key, scan_status, created_by, created_at";

    private final DataSource dataSource;

    public JdbcAttachmentStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates {@code tql_attachment} (per dialect) if it does not exist. */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcAttachmentStore.class,
                    "/tesseraql/db/migration/attachment/V1__attachment.sql");
        } catch (SQLException ex) {
            throw error("Failed to create attachment schema", ex);
        }
    }

    @Override
    public Attachment insert(NewAttachment a) {
        String id = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        String scanStatus = a.scanStatus() == null || a.scanStatus().isBlank()
                ? "clean"
                : a.scanStatus();
        try (Connection cx = dataSource.getConnection();
                PreparedStatement ps = cx.prepareStatement("insert into tql_attachment (" + COLUMNS
                        + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, a.entity());
            ps.setString(3, a.entityId());
            ps.setString(4, a.filename());
            ps.setString(5, a.contentType());
            ps.setLong(6, a.byteSize());
            ps.setString(7, a.checksum());
            ps.setString(8, a.storageKey());
            ps.setString(9, scanStatus);
            ps.setString(10, a.createdBy());
            ps.setTimestamp(11, Timestamp.from(createdAt));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to insert attachment", ex);
        }
        return new Attachment(id, a.entity(), a.entityId(), a.filename(), a.contentType(),
                a.byteSize(), a.checksum(), a.storageKey(), scanStatus, a.createdBy(), createdAt);
    }

    @Override
    public Optional<Attachment> find(String id) {
        try (Connection cx = dataSource.getConnection();
                PreparedStatement ps = cx.prepareStatement("select " + COLUMNS
                        + " from tql_attachment where attachment_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw error("Failed to read attachment", ex);
        }
    }

    @Override
    public List<Attachment> list(String entity, String entityId) {
        List<Attachment> result = new ArrayList<>();
        try (Connection cx = dataSource.getConnection();
                PreparedStatement ps = cx.prepareStatement("select " + COLUMNS
                        + " from tql_attachment where entity = ? and entity_id = ? "
                        + "order by created_at desc")) {
            ps.setString(1, entity);
            ps.setString(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to list attachments", ex);
        }
        return result;
    }

    @Override
    public List<String> deleteOlderThan(Instant cutoff) {
        List<String> storageKeys = new ArrayList<>();
        try (Connection cx = dataSource.getConnection()) {
            try (PreparedStatement select = cx.prepareStatement(
                    "select storage_key from tql_attachment where created_at < ?")) {
                select.setTimestamp(1, Timestamp.from(cutoff));
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        storageKeys.add(rs.getString("storage_key"));
                    }
                }
            }
            try (PreparedStatement delete = cx.prepareStatement(
                    "delete from tql_attachment where created_at < ?")) {
                delete.setTimestamp(1, Timestamp.from(cutoff));
                delete.executeUpdate();
            }
        } catch (SQLException ex) {
            throw error("Failed to delete aged attachments", ex);
        }
        return storageKeys;
    }

    private static Attachment map(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new Attachment(rs.getString("attachment_id"), rs.getString("entity"),
                rs.getString("entity_id"), rs.getString("filename"), rs.getString("content_type"),
                rs.getLong("byte_size"), rs.getString("checksum"), rs.getString("storage_key"),
                rs.getString("scan_status"), rs.getString("created_by"),
                ts == null ? null : ts.toInstant());
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
