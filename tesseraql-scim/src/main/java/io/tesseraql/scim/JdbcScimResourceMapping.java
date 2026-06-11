package io.tesseraql.scim;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A JDBC-backed {@link ScimResourceMapping} (design ch. 10.15): persists the local-id to remote-id
 * link in {@code TQL_SCIM_RESOURCE_MAP} so outbound provisioning survives restarts. Uses portable
 * update-then-insert upsert (no dialect-specific {@code ON CONFLICT}).
 */
public final class JdbcScimResourceMapping implements ScimResourceMapping {

    private final DataSource dataSource;

    public JdbcScimResourceMapping(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Creates the mapping table if absent, from the bundled
     * {@code V1__scim_resource_map.sql} migration script.
     */
    public void ensureSchema() {
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(dataSource,
                    JdbcScimResourceMapping.class,
                    "/tesseraql/db/migration/scim/V1__scim_resource_map.sql");
        } catch (SQLException ex) {
            throw new ScimException(500, null,
                    "Cannot create SCIM mapping table: " + ex.getMessage());
        }
    }

    @Override
    public Optional<String> remoteId(String localId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select remote_id from tql_scim_resource_map where local_id = ?")) {
            statement.setString(1, localId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM mapping lookup failed: " + ex.getMessage());
        }
    }

    @Override
    public void put(String localId, String remoteId) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "update tql_scim_resource_map set remote_id = ? where local_id = ?")) {
                update.setString(1, remoteId);
                update.setString(2, localId);
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into tql_scim_resource_map (local_id, remote_id) values (?, ?)")) {
                insert.setString(1, localId);
                insert.setString(2, remoteId);
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM mapping save failed: " + ex.getMessage());
        }
    }

    @Override
    public void remove(String localId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "delete from tql_scim_resource_map where local_id = ?")) {
            statement.setString(1, localId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM mapping delete failed: " + ex.getMessage());
        }
    }
}
