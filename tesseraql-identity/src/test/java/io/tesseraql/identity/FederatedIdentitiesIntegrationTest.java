package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The federated identity link against a real store (docs/application-roles.md structural
 * decision 3): a subject links once and re-syncs forever — a login-id change at the IdP moves
 * the same account, mapped attributes converge before the principal resolves, and provisioning
 * mints an opaque user id.
 */
@Testcontainers
class FederatedIdentitiesIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static PGSimpleDataSource dataSource;
    static IdentityService identity;
    static final RealmConfig MANAGED = RealmConfig.managed("main", "main");

    @BeforeAll
    static void seed() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        identity = new IdentityService(name -> dataSource);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    s.execute(ddl);
                }
            }
            s.execute("insert into tql_users (user_id, login_id, display_name, status)"
                    + " values ('user-alice','alice','Alice','ACTIVE')");
            s.execute("insert into tql_users (user_id, login_id, display_name, status)"
                    + " values ('user-carol','carol','Carol','ACTIVE')");
            s.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-keiri','keiri','経理部')");
            s.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('user-alice','r-keiri')");
        }
    }

    @Test
    void adoptsExistingUserByLoginThenRenameResyncsThroughTheLink() throws Exception {
        Principal first = FederatedIdentities.resolve(identity, MANAGED,
                login("idp-a", "subject-alice", "alice", null, null, Map.of()), false)
                .orElseThrow();
        assertThat(first.subject()).isEqualTo("user-alice");
        assertThat(linkedUserId("idp-a", "subject-alice")).isEqualTo("user-alice");

        // The IdP renames the login: the link resolves the same account, the profile re-syncs,
        // and the held role survives the rename.
        Principal renamed = FederatedIdentities.resolve(identity, MANAGED,
                login("idp-a", "subject-alice", "alice.renamed", "Alice Renamed",
                        "alice@corp.example.com", Map.of()),
                false)
                .orElseThrow();
        assertThat(renamed.subject()).isEqualTo("user-alice");
        assertThat(renamed.loginId()).isEqualTo("alice.renamed");
        assertThat(renamed.displayName()).isEqualTo("Alice Renamed");
        assertThat(renamed.roles()).contains("keiri");
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select login_id, email from tql_users"
                        + " where user_id = 'user-alice'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("login_id")).isEqualTo("alice.renamed");
            assertThat(rs.getString("email")).isEqualTo("alice@corp.example.com");
        }
    }

    @Test
    void provisionsAnOpaqueUserAndConvergesAttributes() throws Exception {
        Principal provisioned = FederatedIdentities.resolve(identity, MANAGED,
                login("idp-b", "subject-bob", "bob", "Bob", "bob@example.com",
                        attributes("department", "経理部")),
                true)
                .orElseThrow();
        // The internal key is opaque, never the login id.
        assertThat(provisioned.subject()).isNotEqualTo("bob").isNotBlank();
        assertThat(linkedUserId("idp-b", "subject-bob")).isEqualTo(provisioned.subject());
        // The attribute was written before resolution, so this sign-in already carries it.
        assertThat(provisioned.claims().get("department")).isEqualTo("経理部");

        // A later login moves the attribute; the withheld value would be deleted.
        Principal moved = FederatedIdentities.resolve(identity, MANAGED,
                login("idp-b", "subject-bob", "bob", "Bob", "bob@example.com",
                        attributes("department", "総務部")),
                true)
                .orElseThrow();
        assertThat(moved.subject()).isEqualTo(provisioned.subject());
        assertThat(moved.claims().get("department")).isEqualTo("総務部");

        Principal withheld = FederatedIdentities.resolve(identity, MANAGED,
                login("idp-b", "subject-bob", "bob", "Bob", "bob@example.com",
                        attributes("department", null)),
                true)
                .orElseThrow();
        assertThat(withheld.claims()).doesNotContainKey("department");
    }

    @Test
    void provisionDisabledStaysEmpty() {
        Optional<Principal> resolved = FederatedIdentities.resolve(identity, MANAGED,
                login("idp-c", "subject-nobody", "nobody", null, null, Map.of()), false);
        assertThat(resolved).isEmpty();
    }

    @Test
    void blankSubjectFallsBackToLoginResolution() throws Exception {
        Principal resolved = FederatedIdentities.resolve(identity, MANAGED,
                login("idp-d", null, "carol", null, null, Map.of()), false)
                .orElseThrow();
        assertThat(resolved.subject()).isEqualTo("user-carol");
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "select count(*) from tql_user_identities where provider = 'idp-d'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    private static FederatedIdentities.FederatedLogin login(String provider, String subject,
            String loginId, String displayName, String email, Map<String, String> attributes) {
        return new FederatedIdentities.FederatedLogin(provider, subject, loginId, displayName,
                email, null, attributes);
    }

    /** A single-entry attribute map that, unlike {@code Map.of}, allows a null value. */
    private static Map<String, String> attributes(String name, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(name, value);
        return map;
    }

    private static String linkedUserId(String provider, String subject) throws Exception {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select user_id from tql_user_identities"
                        + " where provider = '" + provider + "'"
                        + " and external_subject = '" + subject + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
