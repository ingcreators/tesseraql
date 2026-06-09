package io.tesseraql.scim;

import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Executes SCIM inbound provisioning against the {@link ScimContract} SQL (design ch. 10.15): create,
 * look up by id, and list users. Each statement is rendered with the SCIM attribute bind values and
 * its rows are mapped back to {@link ScimUser} via {@link ScimUserMapper}.
 */
public final class ScimUserService {

    private final DataSource dataSource;
    private final ScimContract contract;

    public ScimUserService(DataSource dataSource, ScimContract contract) {
        this.dataSource = dataSource;
        this.contract = contract;
    }

    /** Creates a user, returning the persisted resource (with its assigned id). */
    public ScimUser create(ScimUser user) {
        try {
            Map<String, Object> row = queryOne(contract.createSql(), ScimUserMapper.toParams(user));
            return row == null ? user : ScimUserMapper.fromRow(row);
        } catch (SQLException ex) {
            if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                throw new ScimException(409, "uniqueness", "User already exists: " + user.userName());
            }
            throw new ScimException(500, null, "SCIM create failed: " + ex.getMessage());
        }
    }

    /** Looks up a user by service-provider id. */
    public Optional<ScimUser> findById(String id) {
        try {
            Map<String, Object> row = queryOne(contract.findByIdSql(), Map.of("id", id));
            return row == null ? Optional.empty() : Optional.of(ScimUserMapper.fromRow(row));
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM lookup failed: " + ex.getMessage());
        }
    }

    /** Lists a page of users; {@code startIndex} is 1-based per SCIM. */
    public ScimListResponse<ScimUser> list(int startIndex, int count) {
        try {
            List<Map<String, Object>> rows = queryAll(contract.listSql(),
                    Map.of("startIndex", startIndex, "count", count));
            List<ScimUser> users = rows.stream().map(ScimUserMapper::fromRow).toList();
            return ScimListResponse.of(users, users.size(), startIndex);
        } catch (SQLException ex) {
            throw new ScimException(500, null, "SCIM list failed: " + ex.getMessage());
        }
    }

    private Map<String, Object> queryOne(String sql, Map<String, Object> params) throws SQLException {
        List<Map<String, Object>> rows = queryAll(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> queryAll(String sql, Map<String, Object> params)
            throws SQLException {
        BoundSql bound = SqlRenderer.render(sql, params);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            List<BoundParameter> parameters = bound.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i).value());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return readRows(resultSet);
            }
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columns = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                row.put(metaData.getColumnLabel(col), resultSet.getObject(col));
            }
            rows.add(row);
        }
        return rows;
    }
}
