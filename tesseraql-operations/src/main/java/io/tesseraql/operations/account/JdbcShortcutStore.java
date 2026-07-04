package io.tesseraql.operations.account;

import io.tesseraql.core.account.ShortcutStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The JDBC pins/recents store (roadmap Phase 51) over {@code tql_user_shortcut}: portable
 * update-then-insert upsert (a bump is a relabel + touch), and the cap trims the kind's
 * oldest rows inside the same call — the ring both kinds share.
 */
public final class JdbcShortcutStore implements ShortcutStore {

    private final DataSource dataSource;

    public JdbcShortcutStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the shortcut table if absent, from the bundled vendor-aware script. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcShortcutStore.class,
                    "/tesseraql/db/migration/shortcut/V1__user_shortcuts.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create shortcut schema", ex);
        }
    }

    @Override
    public List<Shortcut> list(String tenantId, String subject, String kind, int limit) {
        List<Shortcut> shortcuts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select href, label, touched_at from tql_user_shortcut "
                                + "where tenant_id = ? and subject = ? and kind = ? "
                                + "order by touched_at desc, href")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            ps.setString(3, kind);
            ps.setMaxRows(limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    shortcuts.add(new Shortcut(rs.getString(1), rs.getString(2),
                            rs.getTimestamp(3).toInstant()));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list shortcuts", ex);
        }
        return shortcuts;
    }

    @Override
    public void put(String tenantId, String subject, String kind, String href, String label,
            int cap) {
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update tql_user_shortcut set label = ?, touched_at = ? "
                            + "where tenant_id = ? and subject = ? and kind = ? "
                            + "and href_hash = ?")) {
                update.setString(1, label);
                update.setTimestamp(2, Timestamp.from(now));
                update.setString(3, tenant(tenantId));
                update.setString(4, subject);
                update.setString(5, kind);
                update.setString(6, hash(href));
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            insert into tql_user_shortcut
                              (tenant_id, subject, kind, href_hash, href, label, touched_at)
                            values (?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, tenant(tenantId));
                        insert.setString(2, subject);
                        insert.setString(3, kind);
                        insert.setString(4, hash(href));
                        insert.setString(5, href);
                        insert.setString(6, label);
                        insert.setTimestamp(7, Timestamp.from(now));
                        insert.executeUpdate();
                    }
                }
            }
            trim(connection, tenantId, subject, kind, cap);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save shortcut", ex);
        }
    }

    @Override
    public boolean remove(String tenantId, String subject, String kind, String href) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "delete from tql_user_shortcut where tenant_id = ? and subject = ? "
                                + "and kind = ? and href_hash = ?")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            ps.setString(3, kind);
            ps.setString(4, hash(href));
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to remove shortcut", ex);
        }
    }

    /** Deletes the kind's oldest rows beyond the cap (portable two-step, tiny sets). */
    private void trim(Connection connection, String tenantId, String subject, String kind,
            int cap) throws SQLException {
        List<String> keep = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "select href_hash from tql_user_shortcut "
                        + "where tenant_id = ? and subject = ? and kind = ? "
                        + "order by touched_at desc, href")) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            ps.setString(3, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keep.add(rs.getString(1));
                }
            }
        }
        for (int i = cap; i < keep.size(); i++) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "delete from tql_user_shortcut where tenant_id = ? and subject = ? "
                            + "and kind = ? and href_hash = ?")) {
                ps.setString(1, tenant(tenantId));
                ps.setString(2, subject);
                ps.setString(3, kind);
                ps.setString(4, keep.get(i));
                ps.executeUpdate();
            }
        }
    }

    private static String hash(String href) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(href.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? "" : tenantId;
    }
}
