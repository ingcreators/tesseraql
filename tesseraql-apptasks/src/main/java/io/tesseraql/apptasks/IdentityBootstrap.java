package io.tesseraql.apptasks;

import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Applies the managed realm's standard IAM schema and optionally seeds a bootstrap administrator
 * (design ch. 10.3, 18). The schema script is idempotent ({@code create table if not exists}),
 * and the admin seed runs through the identity pack's bootstrap contracts
 * ({@code seed-admin-user}, {@code ensure-role}, {@code assign-user-role}) - 2-way SQL files the
 * runtime's {@code PasswordVerifier} conventions match - so the goal can run on every deploy.
 * Passwords are hashed with PBKDF2 and never stored or logged in clear text.
 */
public final class IdentityBootstrap {

    private final DataSource dataSource;
    private final IdentityService identity;
    private final RealmConfig realm;
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();

    public IdentityBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
        this.identity = new IdentityService(name -> dataSource);
        this.realm = RealmConfig.managed("bootstrap", "main");
    }

    /** Applies the standard {@code tql_*} schema for the dialect. */
    public void applySchema(String dialect) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(DefaultIdentityPack.schema(dialect));
        }
    }

    /**
     * Creates or updates the administrator, assigns the given role codes and grants the given
     * permission codes to those roles - so e.g. {@code tql.ops.view.*} flows into the principal's
     * permissions through the standard role-permission join.
     */
    public void seedAdmin(String loginId, String password, List<String> roleCodes,
            List<String> permissionCodes) {
        // The internal key is opaque, never derived from the login id (docs/application-roles.md
        // structural decision 3): the seed upserts by login id, so a re-seed keeps the existing
        // row's user_id and only a first seed mints the random one.
        identity.executeUpdate(realm, IdentityContracts.SEED_ADMIN_USER, Map.of(
                "userId", java.util.UUID.randomUUID().toString(),
                "loginId", loginId,
                "displayName", loginId,
                "passwordHash", encoder.encode(password),
                "passwordParams", encoder.defaultParams()));
        String userId = userIdByLogin(loginId);
        for (String permissionCode : permissionCodes) {
            identity.executeUpdate(realm, IdentityContracts.ENSURE_PERMISSION, Map.of(
                    "permissionId", permissionCode,
                    "permissionCode", permissionCode,
                    "permissionName", permissionCode));
        }
        for (String roleCode : roleCodes) {
            identity.executeUpdate(realm, IdentityContracts.ENSURE_ROLE, Map.of(
                    "roleId", roleCode, "roleCode", roleCode, "roleName", roleCode));
            identity.executeUpdate(realm, IdentityContracts.ASSIGN_USER_ROLE, Map.of(
                    "userId", userId, "roleCode", roleCode));
            for (String permissionCode : permissionCodes) {
                identity.executeUpdate(realm, IdentityContracts.ASSIGN_ROLE_PERMISSION, Map.of(
                        "roleCode", roleCode, "permissionCode", permissionCode));
            }
        }
    }

    /** The seeded administrator's opaque user id, resolved through the standard contract. */
    private String userIdByLogin(String loginId) {
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("loginId", loginId);
        params.put("tenantId", null);
        List<Map<String, Object>> users = identity.execute(realm,
                IdentityContracts.FIND_USER_BY_LOGIN, params);
        if (users.isEmpty()) {
            throw new IllegalStateException("Seeded administrator not found: " + loginId);
        }
        return String.valueOf(users.get(0).get("user_id"));
    }
}
