package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.BoundParameter;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.security.Principal;
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
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Executes Identity SQL Contracts against a realm's datasource and resolves principals
 * (design ch. 10.5, 10.9.2). The same standardized result aliases are returned whether the realm
 * is managed (standard schema) or sql (existing database), so callers are schema-agnostic.
 */
public final class IdentityService {

    private static final TqlErrorCode EXEC_ERROR = new TqlErrorCode(TqlDomain.IAM, 1002);
    private static final TqlErrorCode NO_DATASOURCE = new TqlErrorCode(TqlDomain.IAM, 1003);
    /** TQL-IAM-4030: a write was attempted on a realm whose capability is read-only. */
    public static final TqlErrorCode READ_ONLY = new TqlErrorCode(TqlDomain.IAM, 4030);

    private final Function<String, DataSource> datasources;

    public IdentityService(Function<String, DataSource> datasources) {
        this.datasources = datasources;
    }

    /** Executes a contract and returns the rows with their contract-defined aliases. */
    public List<Map<String, Object>> execute(RealmConfig realm, String contract,
            Map<String, Object> params) {
        String sql = new ContractResolver(realm).resolve(contract);
        BoundSql bound = SqlRenderer.render(sql, params);
        DataSource dataSource = datasources.apply(realm.datasource());
        if (dataSource == null) {
            throw new TqlException(NO_DATASOURCE,
                    "No datasource '" + realm.datasource() + "' for realm " + realm.id());
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                BoundParameter parameter = bound.parameters().get(i);
                statement.setObject(i + 1, parameter.value());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return readRows(resultSet);
            }
        } catch (SQLException ex) {
            throw TqlException.builder(EXEC_ERROR)
                    .message("Contract '" + contract + "' failed: " + ex.getMessage())
                    .cause(ex)
                    .build();
        }
    }

    /**
     * Executes a write contract (update/insert/delete), returning the affected row count. Rejected
     * with {@link #READ_ONLY} when the realm's user management capability is not readWrite
     * (design ch. 10.7.3).
     */
    public int executeUpdate(RealmConfig realm, String contract, Map<String, Object> params) {
        if (!realm.capabilities().userWriteAllowed()) {
            throw new TqlException(READ_ONLY,
                    "Realm '" + realm.id() + "' does not allow write contract '" + contract + "'");
        }
        String sql = new ContractResolver(realm).resolve(contract);
        BoundSql bound = SqlRenderer.render(sql, params);
        DataSource dataSource = datasources.apply(realm.datasource());
        if (dataSource == null) {
            throw new TqlException(NO_DATASOURCE,
                    "No datasource '" + realm.datasource() + "' for realm " + realm.id());
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                statement.setObject(i + 1, bound.parameters().get(i).value());
            }
            return statement.executeUpdate();
        } catch (SQLException ex) {
            throw TqlException.builder(EXEC_ERROR)
                    .message("Write contract '" + contract + "' failed: " + ex.getMessage())
                    .cause(ex)
                    .build();
        }
    }

    /** Resolves the full principal (user, roles, permissions, groups) for a login id. */
    public Optional<Principal> resolvePrincipal(RealmConfig realm, String loginId, String tenantId) {
        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("loginId", loginId);
        lookup.put("tenantId", tenantId);
        List<Map<String, Object>> users = execute(realm, IdentityContracts.FIND_USER_BY_LOGIN, lookup);
        if (users.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> user = users.get(0);
        Object userId = user.get("user_id");
        Map<String, Object> byUser = Map.of("userId", userId);

        return Optional.of(new Principal(
                asString(userId),
                asString(user.get("login_id")),
                asString(user.get("display_name")),
                asString(user.get("tenant_id")),
                column(execute(realm, IdentityContracts.FIND_GROUPS_BY_USER_ID, byUser), "group_code"),
                column(execute(realm, IdentityContracts.FIND_ROLES_BY_USER_ID, byUser), "role_code"),
                column(execute(realm, IdentityContracts.FIND_PERMISSIONS_BY_USER_ID, byUser),
                        "permission_code"),
                user));
    }

    private static List<String> column(List<Map<String, Object>> rows, String alias) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(alias);
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columnCount; col++) {
                row.put(metaData.getColumnLabel(col), resultSet.getObject(col));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
