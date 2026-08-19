package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The application-role store surface (docs/application-roles.md slice 2) against a real
 * store: validity windows filter at resolution, direct grants reach the principal, grant
 * attribution carries the application axis, and the role capability gates writes.
 */
@Testcontainers
class RoleStoreIntegrationTest {

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
                    + " values ('u1','alice','alice','ACTIVE')");
            s.execute("insert into tql_roles (role_id, role_code, role_name, application)"
                    + " values ('r-app','orders.approver','承認者','orders')");
            s.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-stack','keiri','経理部')");
            s.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-old','expired.role','expired')");
            s.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-future','future.role','future')");
            s.execute("insert into tql_permissions values ('orders.approve','orders.approve','x')");
            s.execute("insert into tql_permissions values ('old.p','old.p','x')");
            s.execute("insert into tql_permissions values ('direct.p','direct.p','x')");
            s.execute("insert into tql_permissions values ('gone.p','gone.p','x')");
            s.execute("insert into tql_role_permissions values ('r-app','orders.approve')");
            s.execute("insert into tql_role_permissions values ('r-old','old.p')");
            s.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r-app')");
            s.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r-stack')");
            s.execute("insert into tql_user_roles (user_id, role_id, ends_at)"
                    + " values ('u1','r-old', current_timestamp - interval '1 day')");
            s.execute("insert into tql_user_roles (user_id, role_id, starts_at)"
                    + " values ('u1','r-future', current_timestamp + interval '1 day')");
            s.execute("insert into tql_user_permissions (user_id, permission_id)"
                    + " values ('u1','direct.p')");
            s.execute("insert into tql_user_permissions (user_id, permission_id, ends_at)"
                    + " values ('u1','gone.p', current_timestamp - interval '1 hour')");
        }
    }

    @Test
    void windowsFilterAtResolutionAndDirectGrantsArrive() {
        Principal p = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(p.roles()).contains("orders.approver", "keiri")
                .doesNotContain("expired.role", "future.role");
        assertThat(p.permissions()).contains("orders.approve", "direct.p")
                .doesNotContain("old.p", "gone.p");
        assertThat(p.directPermissions()).containsExactly("direct.p");
        assertThat(p.roleGrants()).anySatisfy(grant -> {
            assertThat(grant.role()).isEqualTo("orders.approver");
            assertThat(grant.application()).isEqualTo("orders");
            assertThat(grant.permissions()).containsExactly("orders.approve");
        });
        assertThat(p.roleGrants()).anySatisfy(grant -> {
            assertThat(grant.role()).isEqualTo("keiri");
            assertThat(grant.application()).isNull();
        });
    }

    @Test
    void theRoleCapabilityGatesWritesWithItsOwnCode() {
        RealmConfig readOnly = RealmConfig.managed("main", "main", Capabilities.readOnly());
        assertThatThrownBy(() -> identity.executeUpdate(readOnly,
                IdentityContracts.CREATE_ROLE, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("role management");
    }

    @Test
    void anApplicationRolesCodeCarriesItsApplicationsName() {
        assertThatThrownBy(() -> RoleAdmin.createRole(identity, MANAGED,
                "approver", null, "orders"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("first segment");
    }

    /** Slice 3: declaration → store, converge on re-declaration, orphan on removal, revive. */
    @Test
    void declaredRolesReconcileConvergeOrphanAndRevive() {
        var approver = new io.tesseraql.yaml.app.DeclaredRoles.DeclaredRole(
                "shop.approver", "承認者", java.util.List.of("shop.approve"));
        var viewer = new io.tesseraql.yaml.app.DeclaredRoles.DeclaredRole(
                "shop.viewer", null, java.util.List.of());
        assertThat(DeclaredRoleReconciler.reconcile(identity, MANAGED, "shop",
                java.util.List.of(approver, viewer))).isEmpty();

        RoleAdmin.assignRole(identity, MANAGED, "u1", "shop.approver", "", "");
        Principal p = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        // The declared bundle plus the implicit use atom — the fence implication as a row.
        assertThat(p.permissions()).contains("shop.approve", "tql.app.use.shop");
        assertThat(p.roleGrants()).anySatisfy(grant -> {
            assertThat(grant.role()).isEqualTo("shop.approver");
            assertThat(grant.application()).isEqualTo("shop");
        });

        // Re-declare with a different bundle and without the viewer: the bundle converges,
        // the viewer is orphaned (kept, stamped), and nothing is deleted.
        var changed = new io.tesseraql.yaml.app.DeclaredRoles.DeclaredRole(
                "shop.approver", "承認者", java.util.List.of("shop.read"));
        assertThat(DeclaredRoleReconciler.reconcile(identity, MANAGED, "shop",
                java.util.List.of(changed))).containsExactly("shop.viewer");
        Principal after = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(after.permissions()).contains("shop.read", "tql.app.use.shop")
                .doesNotContain("shop.approve");
        assertThat(identity.execute(MANAGED, IdentityContracts.LIST_ROLES_BY_APPLICATION,
                Map.of("application", "shop")))
                .anySatisfy(row -> {
                    assertThat(row.get("role_code")).isEqualTo("shop.viewer");
                    assertThat(row.get("source")).isEqualTo("orphaned");
                });

        // Re-declaring the orphan revives it.
        DeclaredRoleReconciler.reconcile(identity, MANAGED, "shop",
                java.util.List.of(changed, viewer));
        assertThat(identity.execute(MANAGED, IdentityContracts.LIST_ROLES_BY_APPLICATION,
                Map.of("application", "shop")))
                .allSatisfy(row -> assertThat(row.get("source")).isEqualTo("declared"));

        RoleAdmin.unassignRole(identity, MANAGED, "u1", "shop.approver");
    }

    @Test
    void adminAssignmentWithAWindowRoundTrips() {
        RoleAdmin.assignRole(identity, MANAGED, "u1", "orders.approver",
                "2020-01-01T00:00", "2020-06-01T00:00");
        Principal expiredNow = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(expiredNow.roles()).doesNotContain("orders.approver");

        RoleAdmin.assignRole(identity, MANAGED, "u1", "orders.approver", "", "");
        Principal back = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(back.roles()).contains("orders.approver");
    }
}
