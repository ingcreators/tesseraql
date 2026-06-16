package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the Identity SQL Contract (design ch. 10.5, 10.7): the same principal
 * resolution works against the managed standard schema and an existing database, by swapping the
 * contract SQL only.
 */
@Testcontainers
class IdentityServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static IdentityService service;
    static Path legacySqlRoot;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource dataSource = dataSource();
        Function<String, DataSource> datasources = name -> dataSource;
        service = new IdentityService(datasources);
        seedManaged(dataSource);
        seedLegacy(dataSource);
        legacySqlRoot = writeLegacyContracts();
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (legacySqlRoot != null) {
            deleteRecursively(legacySqlRoot);
        }
    }

    @Test
    void resolvesPrincipalFromManagedRealm() {
        RealmConfig realm = RealmConfig.managed("local", "main");
        Optional<Principal> principal = service.resolvePrincipal(realm, "admin", "tenant-a");

        assertThat(principal).isPresent();
        Principal p = principal.orElseThrow();
        assertThat(p.subject()).isEqualTo("u1");
        assertThat(p.loginId()).isEqualTo("admin");
        assertThat(p.roles()).contains("USER_READ", "SALES_ROLE"); // direct + group-derived
        assertThat(p.permissions()).contains("users:read");
        assertThat(p.groups()).contains("sales");
    }

    @Test
    void resolvesPrincipalFromExistingDbViaSwappedContracts() {
        RealmConfig realm = RealmConfig.sql("legacy", "main", legacySqlRoot);
        Optional<Principal> principal = service.resolvePrincipal(realm, "sato", null);

        assertThat(principal).isPresent();
        Principal p = principal.orElseThrow();
        assertThat(p.subject()).isEqualTo("u100");
        assertThat(p.loginId()).isEqualTo("sato");
        assertThat(p.roles()).contains("R_READ");
        assertThat(p.permissions()).contains("users:read");
    }

    @Test
    void writeContractDisablesUserOnReadWriteRealm() {
        RealmConfig realm = RealmConfig.managed("local", "main"); // readWrite by default
        int affected = service.executeUpdate(realm, "disable-user", Map.of("userId", "u2"));

        assertThat(affected).isEqualTo(1);
        var rows = service.execute(realm, IdentityContracts.FIND_USER_BY_ID,
                Map.of("userId", "u2"));
        assertThat(rows.get(0).get("status")).isEqualTo("DISABLED");
    }

    @Test
    void writeContractRejectedOnReadOnlyRealm() {
        RealmConfig realm = RealmConfig.managed("local", "main", Capabilities.readOnly());
        org.assertj.core.api.Assertions
                .assertThatThrownBy(
                        () -> service.executeUpdate(realm, "disable-user", Map.of("userId", "u1")))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-IAM-4030");
    }

    @Test
    void unknownLoginResolvesToEmpty() {
        RealmConfig realm = RealmConfig.managed("local", "main");
        assertThat(service.resolvePrincipal(realm, "nobody", "tenant-a")).isEmpty();
    }

    private static void seedManaged(DataSource dataSource) throws SQLException {
        runScript(dataSource, DefaultIdentityPack.schema("postgres"));
        runScript(dataSource,
                """
                        insert into tql_users (user_id, login_id, display_name, status, tenant_id)
                          values ('u1','admin','Administrator','ACTIVE','tenant-a'),
                                 ('u2','bob','Bob','ACTIVE','tenant-a');
                        insert into tql_roles (role_id, role_code, role_name)
                          values ('r1','USER_READ','User Read'), ('r2','SALES_ROLE','Sales');
                        insert into tql_permissions (permission_id, permission_code, permission_name)
                          values ('p1','users:read','Read users');
                        insert into tql_user_roles (user_id, role_id) values ('u1','r1');
                        insert into tql_role_permissions (role_id, permission_id) values ('r1','p1');
                        insert into tql_groups (group_id, group_code, group_name) values ('g1','sales','Sales');
                        insert into tql_user_groups (user_id, group_id) values ('u1','g1');
                        insert into tql_group_roles (group_id, role_id) values ('g1','r2');
                        """);
    }

    private static void seedLegacy(DataSource dataSource) throws SQLException {
        runScript(dataSource,
                """
                        create table app_user (
                          user_no varchar(64) primary key, login_name varchar(200),
                          full_name varchar(200), mail_address varchar(320), company_code varchar(64),
                          deleted_flag int default 0, locked_flag int default 0);
                        create table app_role (role_cd varchar(64) primary key, role_name varchar(200));
                        create table app_user_role (user_no varchar(64), role_cd varchar(64));
                        create table app_permission (permission_cd varchar(64) primary key, permission_name varchar(200));
                        create table app_role_permission (role_cd varchar(64), permission_cd varchar(64));
                        insert into app_user (user_no, login_name, full_name, mail_address, company_code)
                          values ('u100','sato','Sato Taro','sato@example.com','tenant-a');
                        insert into app_role (role_cd, role_name) values ('R_READ','Reader');
                        insert into app_user_role (user_no, role_cd) values ('u100','R_READ');
                        insert into app_permission (permission_cd, permission_name) values ('users:read','Read');
                        insert into app_role_permission (role_cd, permission_cd) values ('R_READ','users:read');
                        """);
    }

    private static Path writeLegacyContracts() throws IOException {
        Path root = Files.createTempDirectory("tesseraql-legacy-realm");
        Files.writeString(root.resolve("find-user-by-login.sql"),
                """
                        select u.user_no as user_id, u.login_name as login_id, u.full_name as display_name,
                               u.mail_address as email,
                               case when u.deleted_flag = 0 and u.locked_flag = 0 then 'ACTIVE' else 'DISABLED' end as status,
                               u.company_code as tenant_id, 0 as version
                        from app_user u
                        where u.login_name = /* loginId */ 'admin'
                        /*%if tenantId != null */ and u.company_code = /* tenantId */ 'tenant-a' /*%end*/;
                        """);
        Files.writeString(root.resolve("find-roles-by-user-id.sql"),
                """
                        select r.role_cd as role_id, r.role_cd as role_code, r.role_name as role_name, 'DIRECT' as grant_type
                        from app_user_role ur join app_role r on r.role_cd = ur.role_cd
                        where ur.user_no = /* userId */ 'u100';
                        """);
        Files.writeString(root.resolve("find-permissions-by-user-id.sql"),
                """
                        select distinct p.permission_cd as permission_id, p.permission_cd as permission_code,
                               p.permission_name as permission_name
                        from app_user_role ur
                          join app_role_permission rp on rp.role_cd = ur.role_cd
                          join app_permission p on p.permission_cd = rp.permission_cd
                        where ur.user_no = /* userId */ 'u100';
                        """);
        Files.writeString(root.resolve("find-groups-by-user-id.sql"),
                """
                        select null as group_id, null as group_code, null as group_name, null as tenant_id where 1 = 0;
                        """);
        return root;
    }

    private static void runScript(DataSource dataSource, String script) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
