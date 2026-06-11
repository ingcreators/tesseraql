package io.tesseraql.maven;

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
final class IdentityBootstrap {

    private final DataSource dataSource;
    private final IdentityService identity;
    private final RealmConfig realm;
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();

    IdentityBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
        this.identity = new IdentityService(name -> dataSource);
        this.realm = RealmConfig.managed("bootstrap", "main");
    }

    /** Applies the standard {@code tql_*} schema for the dialect. */
    void applySchema(String dialect) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(DefaultIdentityPack.schema(dialect));
        }
    }

    /** Creates or updates the administrator and assigns the given role codes. */
    void seedAdmin(String loginId, String password, List<String> roleCodes) {
        identity.executeUpdate(realm, IdentityContracts.SEED_ADMIN_USER, Map.of(
                "userId", loginId,
                "loginId", loginId,
                "displayName", loginId,
                "passwordHash", encoder.encode(password),
                "passwordParams", encoder.defaultParams()));
        for (String roleCode : roleCodes) {
            identity.executeUpdate(realm, IdentityContracts.ENSURE_ROLE, Map.of(
                    "roleId", roleCode, "roleCode", roleCode, "roleName", roleCode));
            identity.executeUpdate(realm, IdentityContracts.ASSIGN_USER_ROLE, Map.of(
                    "userId", loginId, "roleCode", roleCode));
        }
    }
}
