package io.tesseraql.maven;

import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

/**
 * Applies the managed realm's standard IAM schema and optionally seeds a bootstrap administrator
 * (design ch. 10.3, 18). The schema script is idempotent ({@code create table if not exists}), and
 * the admin seed upserts by login id, so the goal can run on every deploy. Passwords are hashed
 * with the same PBKDF2 encoder the runtime verifies against and are never stored or logged in
 * clear text.
 */
final class IdentityBootstrap {

    private final DataSource dataSource;
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();

    IdentityBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Applies the standard {@code tql_*} schema for the dialect. */
    void applySchema(String dialect) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(DefaultIdentityPack.schema(dialect));
        }
    }

    /** Creates or updates the administrator and assigns the given role codes (PostgreSQL). */
    void seedAdmin(String loginId, String password, List<String> roleCodes) throws SQLException {
        String hash = encoder.encode(password);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    insert into tql_users
                      (user_id, login_id, display_name, status,
                       password_hash, password_algo, password_params)
                    values (?, ?, ?, 'ACTIVE', ?, 'pbkdf2', ?)
                    on conflict (login_id) do update set
                      status = 'ACTIVE',
                      password_hash = excluded.password_hash,
                      password_algo = excluded.password_algo,
                      password_params = excluded.password_params
                    """)) {
                upsert.setString(1, loginId);
                upsert.setString(2, loginId);
                upsert.setString(3, loginId);
                upsert.setString(4, hash);
                upsert.setString(5, encoder.defaultParams());
                upsert.executeUpdate();
            }
            for (String roleCode : roleCodes) {
                assignRole(connection, loginId, roleCode);
            }
        }
    }

    private static void assignRole(Connection connection, String userId, String roleCode)
            throws SQLException {
        try (PreparedStatement role = connection.prepareStatement("""
                insert into tql_roles (role_id, role_code, role_name)
                values (?, ?, ?)
                on conflict (role_code) do nothing
                """)) {
            role.setString(1, roleCode);
            role.setString(2, roleCode);
            role.setString(3, roleCode);
            role.executeUpdate();
        }
        try (PreparedStatement assignment = connection.prepareStatement("""
                insert into tql_user_roles (user_id, role_id)
                select ?, role_id from tql_roles where role_code = ?
                on conflict do nothing
                """)) {
            assignment.setString(1, userId);
            assignment.setString(2, roleCode);
            assignment.executeUpdate();
        }
    }
}
