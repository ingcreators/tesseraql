package io.tesseraql.operations.account;

import io.tesseraql.core.account.PreferenceStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The JDBC per-user preference store (roadmap Phase 48): one row per (tenant, subject, key)
 * in {@code tql_user_preference}. Writes use the portable update-then-insert upsert (the
 * {@code JdbcOrgUnitStore} pattern) so every supported dialect behaves identically; a lost
 * race on first write falls back to the update leg.
 */
public final class JdbcPreferenceStore implements PreferenceStore {

    /** Bounds enforced at the store: preferences are small by contract (docs/account.md). */
    static final int MAX_KEY_LENGTH = 128;

    static final int MAX_VALUE_LENGTH = 2000;

    private final DataSource dataSource;

    public JdbcPreferenceStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the preference table if absent, from the bundled vendor-aware script. */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcPreferenceStore.class,
                    "/tesseraql/db/migration/account/V1__user_preferences.sql");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create user preference schema", ex);
        }
    }

    @Override
    public Map<String, String> preferences(String tenantId, String subject) {
        Map<String, String> preferences = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        select pref_key, pref_value from tql_user_preference
                        where tenant_id = ? and subject = ? order by pref_key
                        """)) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    preferences.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read user preferences", ex);
        }
        return preferences;
    }

    @Override
    public void put(String tenantId, String subject, String key, String value) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Invalid preference key");
        }
        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Invalid preference value");
        }
        try (Connection connection = dataSource.getConnection()) {
            if (update(connection, tenantId, subject, key, value) == 0) {
                try {
                    insert(connection, tenantId, subject, key, value);
                } catch (SQLException raced) {
                    // Another node inserted first; the update leg now finds the row.
                    if (update(connection, tenantId, subject, key, value) == 0) {
                        throw raced;
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to write user preference", ex);
        }
    }

    @Override
    public void remove(String tenantId, String subject, String key) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement("""
                        delete from tql_user_preference
                        where tenant_id = ? and subject = ? and pref_key = ?
                        """)) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            ps.setString(3, key);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to remove user preference", ex);
        }
    }

    private int update(Connection connection, String tenantId, String subject, String key,
            String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                update tql_user_preference set pref_value = ?, updated_at = ?
                where tenant_id = ? and subject = ? and pref_key = ?
                """)) {
            ps.setString(1, value);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, tenant(tenantId));
            ps.setString(4, subject);
            ps.setString(5, key);
            return ps.executeUpdate();
        }
    }

    private void insert(Connection connection, String tenantId, String subject, String key,
            String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                insert into tql_user_preference
                  (tenant_id, subject, pref_key, pref_value, updated_at)
                values (?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, tenant(tenantId));
            ps.setString(2, subject);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? "" : tenantId;
    }
}
