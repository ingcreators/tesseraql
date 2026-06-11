package io.tesseraql.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.password.PasswordVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the migrate and identity-schema goal logic (design ch. 18) against
 * PostgreSQL.
 */
@Testcontainers
class BootstrapGoalsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    Path appHome;

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    @Test
    void migrateAppliesScriptsIntoThePerAppHistoryTableAndIsIdempotent() throws Exception {
        Path migrations = appHome.resolve("db/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__orders.sql"),
                "create table orders (id serial primary key, item varchar(200));");
        Files.writeString(migrations.resolve("V2__orders_status.sql"),
                "alter table orders add column status varchar(32) default 'NEW';");
        // Vendor-specific scripts in the migration-<vendor> sibling layer over the common set.
        Path vendor = appHome.resolve("db/migration-postgresql");
        Files.createDirectories(vendor);
        Files.writeString(vendor.resolve("V3__orders_note.sql"),
                "alter table orders add column if not exists note text;");
        DataSource dataSource = dataSource();

        AppMigrator.Result first = AppMigrator.migrate(appHome, "Order-App", "main", dataSource)
                .orElseThrow();
        assertThat(first.applied()).isEqualTo(3);
        assertThat(first.historyTable()).isEqualTo("tql_schema_history_order_app");

        // Re-running applies nothing: the history table remembers all versions.
        AppMigrator.Result second = AppMigrator.migrate(appHome, "Order-App", "main", dataSource)
                .orElseThrow();
        assertThat(second.applied()).isZero();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet versions = statement.executeQuery(
                        "select count(*) from tql_schema_history_order_app"
                                + " where version is not null and version <> '0'")) {
            versions.next();
            assertThat(versions.getInt(1)).isEqualTo(3);
        }
    }

    @Test
    void namedDatasourceMigratesItsOwnSetIntoItsOwnHistoryTable() throws Exception {
        Path migrations = appHome.resolve("db/analytics/migration");
        Files.createDirectories(migrations);
        Files.writeString(migrations.resolve("V1__facts.sql"),
                "create table facts (id serial primary key, metric varchar(200));");
        DataSource dataSource = dataSource();

        AppMigrator.Result result = AppMigrator
                .migrate(appHome, "Order-App", "analytics", dataSource).orElseThrow();

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.historyTable()).isEqualTo("tql_schema_history_order_app__analytics");
        // The main set is untouched: a missing db/migration directory stays a no-op.
        assertThat(AppMigrator.migrate(appHome, "Order-App", "main", dataSource)).isEmpty();
    }

    @Test
    void migrateWithoutMigrationDirectoryIsANoOp() {
        assertThat(AppMigrator.migrate(appHome.resolve("nowhere"), "x", "main", dataSource()))
                .isEmpty();
    }

    @Test
    void identitySchemaAppliesIdempotentlyAndSeedsAVerifiableAdmin() throws Exception {
        DataSource dataSource = dataSource();
        IdentityBootstrap bootstrap = new IdentityBootstrap(dataSource);
        bootstrap.applySchema("postgres");
        bootstrap.applySchema("postgres");

        bootstrap.seedAdmin("admin", "first-password", List.of("iam.admin"),
                List.of("ops.app.*"));
        // Re-seeding rotates the password in place instead of failing on the unique login.
        bootstrap.seedAdmin("admin", "rotated-password", List.of("iam.admin"),
                List.of("ops.app.*"));

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet admin = statement.executeQuery(
                        "select u.password_hash, u.password_algo, u.password_params, r.role_code"
                                + " from tql_users u"
                                + " join tql_user_roles ur on ur.user_id = u.user_id"
                                + " join tql_roles r on r.role_id = ur.role_id"
                                + " where u.login_id = 'admin'")) {
            assertThat(admin.next()).isTrue();
            String hash = admin.getString("password_hash");
            assertThat(hash).doesNotContain("rotated-password");
            assertThat(new PasswordVerifier().verify("rotated-password", hash,
                    admin.getString("password_algo"), admin.getString("password_params"))).isTrue();
            assertThat(new PasswordVerifier().verify("first-password", hash,
                    admin.getString("password_algo"), admin.getString("password_params")))
                    .isFalse();
            assertThat(admin.getString("role_code")).isEqualTo("iam.admin");
            assertThat(admin.next()).isFalse();
        }

        // The granted permission flows to the principal through the standard contract join.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet permissions = statement.executeQuery(
                        "select p.permission_code from tql_user_roles ur"
                                + " join tql_role_permissions rp on rp.role_id = ur.role_id"
                                + " join tql_permissions p on p.permission_id = rp.permission_id"
                                + " join tql_users u on u.user_id = ur.user_id"
                                + " where u.login_id = 'admin'")) {
            assertThat(permissions.next()).isTrue();
            assertThat(permissions.getString(1)).isEqualTo("ops.app.*");
            assertThat(permissions.next()).isFalse();
        }
    }
}
