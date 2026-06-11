package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.util.SqlScripts;
import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.PasswordAuthenticator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Shared assertion for the dialect portability tests: applies the managed identity schema for
 * the dialect, runs every bootstrap contract twice (idempotency), and authenticates the seeded
 * administrator - proving the dialect's schema and MERGE/upsert contract variants end to end.
 */
final class DialectIdentityChecks {

    private DialectIdentityChecks() {
    }

    static void seedAndAuthenticate(DataSource dataSource, String dialect) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : SqlScripts.statements(DefaultIdentityPack.schema(dialect))) {
                statement.execute(sql);
            }
        }

        IdentityService identity = new IdentityService(name -> dataSource, dialect);
        RealmConfig realm = RealmConfig.managed("bootstrap", "main");
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        Map<String, Object> admin = Map.of(
                "userId", "admin", "loginId", "admin", "displayName", "admin",
                "passwordHash", encoder.encode("s3cret"),
                "passwordParams", encoder.defaultParams());
        // Twice: the second pass exercises the dialect's matched/ignore upsert path.
        identity.executeUpdate(realm, IdentityContracts.SEED_ADMIN_USER, admin);
        identity.executeUpdate(realm, IdentityContracts.SEED_ADMIN_USER, admin);
        identity.executeUpdate(realm, IdentityContracts.ENSURE_ROLE,
                Map.of("roleId", "ADMIN", "roleCode", "ADMIN", "roleName", "ADMIN"));
        identity.executeUpdate(realm, IdentityContracts.ENSURE_ROLE,
                Map.of("roleId", "ADMIN", "roleCode", "ADMIN", "roleName", "ADMIN"));
        identity.executeUpdate(realm, IdentityContracts.ENSURE_PERMISSION,
                Map.of("permissionId", "ops.app.*", "permissionCode", "ops.app.*",
                        "permissionName", "ops.app.*"));
        identity.executeUpdate(realm, IdentityContracts.ASSIGN_USER_ROLE,
                Map.of("userId", "admin", "roleCode", "ADMIN"));
        identity.executeUpdate(realm, IdentityContracts.ASSIGN_USER_ROLE,
                Map.of("userId", "admin", "roleCode", "ADMIN"));
        identity.executeUpdate(realm, IdentityContracts.ASSIGN_ROLE_PERMISSION,
                Map.of("roleCode", "ADMIN", "permissionCode", "ops.app.*"));

        var principal = new PasswordAuthenticator(identity)
                .authenticate(realm, "admin", "s3cret", null);
        assertThat(principal).isPresent();
        assertThat(principal.get().roles()).contains("ADMIN");
        assertThat(principal.get().permissions()).contains("ops.app.*");
    }
}
