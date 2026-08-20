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

    /** A contract's SQL failed against the realm's datasource. */
    public static final TqlErrorCode EXEC_ERROR = new TqlErrorCode(TqlDomain.IAM, 1002);
    private static final TqlErrorCode NO_DATASOURCE = new TqlErrorCode(TqlDomain.IAM, 1003);
    /** TQL-IAM-4030: a write was attempted on a realm whose capability is read-only. */
    public static final TqlErrorCode READ_ONLY = new TqlErrorCode(TqlDomain.IAM, 4030);

    /** The realm's role capability is not readWrite, so role management is refused. */
    public static final TqlErrorCode ROLE_READ_ONLY = new TqlErrorCode(TqlDomain.IAM, 4031);

    private final Function<String, DataSource> datasources;
    private final String dialect;

    public IdentityService(Function<String, DataSource> datasources) {
        this(datasources, null);
    }

    /** Resolves contract SQL for {@code dialect} (selecting {@code <contract>.<dialect>.sql} variants). */
    public IdentityService(Function<String, DataSource> datasources, String dialect) {
        this.datasources = datasources;
        this.dialect = dialect;
    }

    /** Executes a contract and returns the rows with their contract-defined aliases. */
    public List<Map<String, Object>> execute(RealmConfig realm, String contract,
            Map<String, Object> params) {
        String sql = new ContractResolver(realm, dialect).resolve(contract);
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
        // Two capabilities, two gates (docs/application-roles.md structural decision 6): a
        // role-management write answers to roleManagement, everything else to userManagement.
        if (IdentityContracts.roleManagementContracts().contains(contract)) {
            if (!realm.capabilities().roleWriteAllowed()) {
                throw new TqlException(ROLE_READ_ONLY, "Realm '" + realm.id()
                        + "' does not allow role management contract '" + contract + "'");
            }
        } else if (!realm.capabilities().userWriteAllowed()) {
            throw new TqlException(READ_ONLY,
                    "Realm '" + realm.id() + "' does not allow write contract '" + contract + "'");
        }
        String sql = new ContractResolver(realm, dialect).resolve(contract);
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
    public Optional<Principal> resolvePrincipal(RealmConfig realm, String loginId,
            String tenantId) {
        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("loginId", loginId);
        lookup.put("tenantId", tenantId);
        List<Map<String, Object>> users = execute(realm, IdentityContracts.FIND_USER_BY_LOGIN,
                lookup);
        if (users.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> user = users.get(0);
        Object userId = user.get("user_id");
        Map<String, Object> byUser = Map.of("userId", userId);
        List<String> groups = column(execute(realm, IdentityContracts.FIND_GROUPS_BY_USER_ID,
                byUser), "group_code");

        // Attributes ride the claims, and the enabled assignment rules materialize this
        // user's rule-produced roles before the role and permission reads, so this sign-in
        // already reflects them (docs/application-roles.md structural decision 3). The
        // user row's own columns win a name collision; a sql realm without the optional
        // contracts skips both, exactly as it skips grant attribution.
        Map<String, Object> claims = new LinkedHashMap<>(user);
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            for (Map<String, Object> row : execute(realm,
                    IdentityContracts.LIST_USER_ATTRIBUTES, byUser)) {
                attributes.put(asString(row.get("name")), asString(row.get("value")));
            }
            attributes.forEach(claims::putIfAbsent);
            if (realm.type() == RealmConfig.RealmType.MANAGED
                    && realm.capabilities().roleWriteAllowed()) {
                java.util.Set<String> produced = RoleRules.evaluate(
                        execute(realm, IdentityContracts.FIND_ENABLED_RULE_CONDITIONS,
                                Map.of()),
                        attributes, groups, (ancestor, descendant) -> {
                            List<Map<String, Object>> matched = execute(realm,
                                    IdentityContracts.IS_ORG_DESCENDANT,
                                    Map.of("ancestorId", ancestor,
                                            "descendantId", descendant));
                            return !matched.isEmpty() && ((Number) matched.get(0)
                                    .get("matched")).longValue() > 0;
                        });
                RoleRules.materialize(this, realm, asString(userId), produced);
            }
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
        }

        // Grant attribution (docs/application-roles.md structural decision 4): held roles
        // with their application axis and bundles, plus direct grants — the two optional
        // contracts; a sql realm without them resolves exactly as before, union only.
        List<Map<String, Object>> grantRows;
        List<String> direct;
        try {
            grantRows = execute(realm, IdentityContracts.FIND_ROLE_GRANTS_BY_USER_ID, byUser);
            direct = column(execute(realm,
                    IdentityContracts.FIND_DIRECT_PERMISSIONS_BY_USER_ID, byUser),
                    "permission_code");
        } catch (TqlException ex) {
            if (!ContractResolver.MISSING_CONTRACT.equals(ex.code())) {
                throw ex;
            }
            grantRows = List.of();
            direct = List.of();
        }
        // Context conditions ride the grant (docs/access-governance.md structural decision 8):
        // read once here, evaluated per request against that request's own context. A realm
        // without the contract or the table has none, which is an unnarrowed grant — what
        // every deployment had before conditions existed.
        Map<String, List<Principal.RoleGrant.Condition>> conditions = grantRows.isEmpty()
                ? Map.of()
                : RoleConditions.byRole(this, realm, asString(userId));
        return Optional.of(new Principal(
                asString(userId),
                asString(user.get("login_id")),
                asString(user.get("display_name")),
                asString(user.get("tenant_id")),
                groups,
                column(execute(realm, IdentityContracts.FIND_ROLES_BY_USER_ID, byUser),
                        "role_code"),
                column(execute(realm, IdentityContracts.FIND_PERMISSIONS_BY_USER_ID, byUser),
                        "permission_code"),
                claims,
                roleGrants(grantRows, conditions),
                direct));
    }

    /** Groups the role-grant rows (role_code, application, permission_code) by role. */
    private static List<Principal.RoleGrant> roleGrants(List<Map<String, Object>> rows,
            Map<String, List<Principal.RoleGrant.Condition>> conditions) {
        Map<String, Map.Entry<String, List<String>>> byRole = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String role = asString(row.get("role_code"));
            if (role == null) {
                continue;
            }
            Map.Entry<String, List<String>> entry = byRole.computeIfAbsent(role,
                    key -> Map.entry(asString(row.get("application")) == null
                            ? ""
                            : asString(row.get("application")), new ArrayList<>()));
            String permission = asString(row.get("permission_code"));
            if (permission != null) {
                entry.getValue().add(permission);
            }
        }
        List<Principal.RoleGrant> grants = new ArrayList<>();
        for (Map.Entry<String, Map.Entry<String, List<String>>> entry : byRole.entrySet()) {
            grants.add(new Principal.RoleGrant(entry.getKey(),
                    entry.getValue().getKey().isEmpty() ? null : entry.getValue().getKey(),
                    entry.getValue().getValue(),
                    conditions.getOrDefault(entry.getKey(), List.of())));
        }
        return grants;
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

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columnCount; col++) {
                row.put(io.tesseraql.core.dialect.Labels.normalize(
                        dialect, metaData.getColumnLabel(col)), resultSet.getObject(col));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Whether a failed <em>read</em> means the store simply does not have this feature
     * installed, rather than that something went wrong (docs/access-governance.md).
     *
     * <p>Two conditions look identical to a caller and mean the same thing. A {@code sql}
     * realm whose pack has no such contract raises {@link ContractResolver#MISSING_CONTRACT};
     * a managed realm whose schema predates the feature has the contract and no table, which
     * raises {@link #EXEC_ERROR}. The standard schema is applied with
     * {@code create table if not exists}, so an existing store gains new tables only when the
     * operator re-runs it — an uninstalled feature is a normal state, not a fault.
     *
     * <p>Reads degrade on both. <b>Writes never do</b>: a grant that silently did not happen,
     * or a trail row that silently was not written, is the failure this campaign exists to
     * prevent.
     */
    public static boolean featureUnavailable(TqlException ex) {
        return ContractResolver.MISSING_CONTRACT.equals(ex.code()) || EXEC_ERROR.equals(ex.code());
    }
}
