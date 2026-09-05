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

    /** Slice 4: an attribute-matched rule assigns at sign-in; manual assignments survive. */
    @Test
    void rulesAssignAtSignInAndManualAssignmentsSurvive() {
        RoleAdmin.createRole(identity, MANAGED, "keiri.member", "経理", "");
        String ruleId = String.valueOf(RoleAdmin.createRule(identity, MANAGED,
                "keiri.member", "department", "eq", "accounting", false).get("created"));
        RoleAdmin.setAttribute(identity, MANAGED, "u1", "department", "accounting");

        Principal matched = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(matched.roles()).contains("keiri.member");
        assertThat(matched.claim().get("department")).isEqualTo("accounting");

        // The attribute flips: the rule assignment converges away at the next sign-in.
        RoleAdmin.setAttribute(identity, MANAGED, "u1", "department", "sales");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .doesNotContain("keiri.member");

        // A manual assignment of the same role is admin provenance: recompute keeps it.
        RoleAdmin.assignRole(identity, MANAGED, "admin", "u1", "keiri.member", "", "");
        RoleRules.recompute(identity, MANAGED, "u1");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .contains("keiri.member");

        RoleAdmin.unassignRole(identity, MANAGED, "admin", "u1", "keiri.member");
        RoleAdmin.deleteAttribute(identity, MANAGED, "u1", "department");
        RoleAdmin.deleteRule(identity, MANAGED, ruleId);
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

        RoleAdmin.assignRole(identity, MANAGED, "admin", "u1", "shop.approver", "", "");
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

        RoleAdmin.unassignRole(identity, MANAGED, "admin", "u1", "shop.approver");
    }

    /**
     * Slice 1 of docs/access-governance.md: both grant write paths leave a row, and the
     * automatic one names the mechanism rather than the person who happened to sign in.
     */
    @Test
    void bothGrantWritePathsRecordTheirChange() {
        RoleAdmin.createRole(identity, MANAGED, "audit.reader", "監査", "");
        RoleAdmin.assignRole(identity, MANAGED, "kenji", "u1", "audit.reader", "",
                "2030-01-01");
        assertThat(historyFor("audit.reader")).anySatisfy(row -> {
            assertThat(row.get("actor")).isEqualTo("kenji");
            assertThat(row.get("change_kind")).isEqualTo(GrantHistory.ROLE_GRANTED);
            assertThat(row.get("source")).isEqualTo(GrantHistory.SOURCE_ADMIN);
            assertThat(row.get("ends_at")).isNotNull();
            assertThat(row.get("subject_login_id")).isEqualTo("alice");
        });

        RoleAdmin.unassignRole(identity, MANAGED, "kenji", "u1", "audit.reader");
        assertThat(historyFor("audit.reader")).anySatisfy(
                row -> assertThat(row.get("change_kind")).isEqualTo(GrantHistory.ROLE_REVOKED));

        // The rule converge is the second path. It is not an HTTP call, which is exactly
        // why the route audit could never have covered it.
        RoleAdmin.createRole(identity, MANAGED, "audit.rule.role", "監査", "");
        String ruleId = String.valueOf(RoleAdmin.createRule(identity, MANAGED,
                "audit.rule.role", "grade", "eq", "manager", false).get("created"));
        RoleAdmin.setAttribute(identity, MANAGED, "u1", "grade", "manager");
        RoleRules.recompute(identity, MANAGED, "u1");
        assertThat(historyFor("audit.rule.role")).anySatisfy(row -> {
            assertThat(row.get("actor")).isEqualTo(GrantHistory.SOURCE_RULE);
            assertThat(row.get("source")).isEqualTo(GrantHistory.SOURCE_RULE);
            assertThat(row.get("change_kind")).isEqualTo(GrantHistory.ROLE_GRANTED);
        });

        RoleAdmin.deleteAttribute(identity, MANAGED, "u1", "grade");
        RoleRules.recompute(identity, MANAGED, "u1");
        assertThat(historyFor("audit.rule.role")).anySatisfy(
                row -> assertThat(row.get("change_kind")).isEqualTo(GrantHistory.ROLE_REVOKED));
        RoleAdmin.deleteRule(identity, MANAGED, ruleId);
    }

    /**
     * A trail too large to show says so, instead of failing the page.
     *
     * <p>The grant trail is the one identity read that meets the bound in normal operation: its
     * contract's own header says no parameter means the whole store, and the store is append-only.
     * Capping it without this would turn a slow audit page into a 500 an operator cannot clear
     * from the page, because the refusal is not a missing feature and nothing else catches it.
     *
     * <p>And it must NOT be answered by widening {@code featureUnavailable}: that method is
     * consulted by every degrading caller in the module, so admitting this code there would make a
     * truncated separation-of-duties read find no conflict.
     */
    @Test
    void aTrailTooLargeToShowSaysSoRatherThanFailingThePage() {
        IdentityService bounded = new IdentityService(name -> dataSource).resultMaxRows(1);
        GrantHistory.record(identity, MANAGED,
                GrantHistory.Change.admin("kenji", "u1", GrantHistory.ROLE_GRANTED, "first"));
        GrantHistory.record(identity, MANAGED,
                GrantHistory.Change.admin("kenji", "u1", GrantHistory.ROLE_GRANTED, "second"));

        Map<String, Object> model = GrantHistory.historyModel(bounded, MANAGED, null, null);

        assertThat(model)
                .containsEntry("available", 0)
                .containsEntry("title", "This trail is too large to show at once.");
        assertThat((String) model.get("reason")).contains("tesseraql.identity.maxRows");
    }

    /** The absent-contract panel keeps its own words, which are the opposite problem. */
    @Test
    void aRealmWithoutTheContractStillSaysItKeepsNoTrail(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path emptyPack) {
        RealmConfig noHistory = RealmConfig.sql("bare", "main", emptyPack,
                Capabilities.readWrite());

        assertThat(GrantHistory.historyModel(identity, noHistory, null, null))
                .containsEntry("available", 0)
                .containsEntry("title", "This realm keeps no grant history.");
    }

    /** A realm whose pack has no history contract keeps its writes and reports no trail. */
    @Test
    void aRealmWithoutTheHistoryContractDegradesRatherThanFailing(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path emptyPack) {
        RealmConfig noHistory = RealmConfig.sql("bare", "main", emptyPack,
                Capabilities.readWrite());
        assertThat(GrantHistory.historyModel(identity, noHistory, null, null))
                .containsEntry("available", 0);
        GrantHistory.record(identity, noHistory,
                GrantHistory.Change.admin("kenji", "u1", GrantHistory.ROLE_GRANTED, "x"));
    }

    /**
     * Slice 2 of docs/access-governance.md: the admin write refuses, the rule converge
     * withholds, and a constraint added over existing grants reports them.
     */
    @Test
    void separationOfDutiesRefusesAtTheAdminWriteAndWithholdsAtTheConverge() {
        RoleAdmin.createRole(identity, MANAGED, "sod.buyer", "購買", "");
        RoleAdmin.createRole(identity, MANAGED, "sod.approver", "承認", "");
        RoleAdmin.createConstraint(identity, MANAGED, "Buyer and approver",
                SeparationOfDuties.BLOCK, "sod.buyer", "sod.approver");

        RoleAdmin.assignRole(identity, MANAGED, "kenji", "u1", "sod.buyer", "", "");
        assertThatThrownBy(() -> RoleAdmin.assignRole(identity, MANAGED, "kenji", "u1",
                "sod.approver", "", ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Buyer and approver")
                .hasMessageContaining("sod.buyer");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .doesNotContain("sod.approver");

        // The refusal wrote nothing at all, the trail included.
        assertThat(historyFor("sod.approver")).isEmpty();

        // The rule converge withholds instead of refusing: sign-in must not fail because
        // two rules disagree, so the conflicting role simply is not granted.
        String ruleId = String.valueOf(RoleAdmin.createRule(identity, MANAGED,
                "sod.approver", "post", "eq", "chief", false).get("created"));
        RoleAdmin.setAttribute(identity, MANAGED, "u1", "post", "chief");
        Principal after = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(after.roles()).contains("sod.buyer").doesNotContain("sod.approver");

        // The existing-violation report is what makes the withheld grant visible.
        RoleAdmin.deleteConstraint(identity, MANAGED, constraintId("Buyer and approver"));
        RoleRules.recompute(identity, MANAGED, "u1");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .contains("sod.approver");
        RoleAdmin.createConstraint(identity, MANAGED, "Buyer and approver",
                SeparationOfDuties.BLOCK, "sod.buyer", "sod.approver");
        assertThat(identity.execute(MANAGED, IdentityContracts.FIND_SOD_VIOLATIONS, Map.of()))
                .anySatisfy(row -> {
                    assertThat(row.get("constraint_name")).isEqualTo("Buyer and approver");
                    assertThat(row.get("login_id")).isEqualTo("alice");
                });

        RoleAdmin.deleteRule(identity, MANAGED, ruleId);
        RoleAdmin.deleteAttribute(identity, MANAGED, "u1", "post");
        RoleAdmin.deleteConstraint(identity, MANAGED, constraintId("Buyer and approver"));
        RoleAdmin.unassignRole(identity, MANAGED, "kenji", "u1", "sod.buyer");
        RoleAdmin.unassignRole(identity, MANAGED, "kenji", "u1", "sod.approver");
    }

    /**
     * Slice 3 of docs/access-governance.md: an eligibility grants nothing, elevating grants
     * a bounded window, and the window is what expires it.
     */
    @Test
    void anEligibilityGrantsNothingUntilItIsTaken() {
        RoleAdmin.createRole(identity, MANAGED, "jit.release", "リリース", "");
        Elevation.grantEligibility(identity, MANAGED, "u1", "jit.release", "30", true);

        // The eligibility is deliberately absent from every resolution read.
        Principal before = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(before.roles()).doesNotContain("jit.release");
        assertThat(before.roleGrants())
                .noneSatisfy(grant -> assertThat(grant.role()).isEqualTo("jit.release"));

        assertThatThrownBy(() -> Elevation.elevate(identity, MANAGED, "u1", "jit.release",
                "10", "")).isInstanceOf(TqlException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> Elevation.elevate(identity, MANAGED, "u1", "jit.release",
                "31", "release 4.2")).isInstanceOf(TqlException.class)
                .hasMessageContaining("between 1 and 30");
        assertThatThrownBy(() -> Elevation.elevate(identity, MANAGED, "u1", "no.such.role",
                "10", "x")).isInstanceOf(TqlException.class).hasMessageContaining("not eligible");

        Elevation.elevate(identity, MANAGED, "u1", "jit.release", "30", "release 4.2");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .contains("jit.release");
        assertThat(historyFor("jit.release")).anySatisfy(row -> {
            assertThat(row.get("source")).isEqualTo(Elevation.SOURCE);
            assertThat(row.get("reason")).isEqualTo("release 4.2");
            assertThat(row.get("ends_at")).isNotNull();
        });

        // Elevating into a role already held is refused: the assignment would collide, and
        // extending a standing grant is a different feature.
        assertThatThrownBy(() -> Elevation.elevate(identity, MANAGED, "u1", "jit.release",
                "10", "again")).isInstanceOf(TqlException.class)
                .hasMessageContaining("already holds");

        Elevation.endElevation(identity, MANAGED, "kenji", "u1", "jit.release");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .doesNotContain("jit.release");
        Elevation.revokeEligibility(identity, MANAGED, "u1", "jit.release");
    }

    /** Ending an elevation never touches a standing admin grant of the same role. */
    @Test
    void endingAnElevationLeavesAStandingGrantAlone() {
        RoleAdmin.createRole(identity, MANAGED, "jit.both", "両方", "");
        RoleAdmin.assignRole(identity, MANAGED, "kenji", "u1", "jit.both", "", "");
        Elevation.endElevation(identity, MANAGED, "kenji", "u1", "jit.both");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .contains("jit.both");
        RoleAdmin.unassignRole(identity, MANAGED, "kenji", "u1", "jit.both");
    }

    /**
     * Slice 4 of docs/access-governance.md: groups are writable, membership carries the
     * same window every other assignment carries, and the trail records joins and leaves.
     */
    @Test
    void groupsAreWritableAndMembershipCarriesAWindow() {
        RoleAdmin.createRole(identity, MANAGED, "grp.reader", "閲覧", "");
        GroupAdmin.createGroup(identity, MANAGED, "SALES", "営業部");
        GroupAdmin.grantRole(identity, MANAGED, "SALES", "grp.reader");
        GroupAdmin.addMember(identity, MANAGED, "kenji", "SALES", "u1", "", "");

        Principal member = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(member.groups()).contains("SALES");
        assertThat(member.roles()).contains("grp.reader");
        assertThat(historyFor("SALES")).anySatisfy(row -> {
            assertThat(row.get("change_kind")).isEqualTo(GrantHistory.GROUP_JOINED);
            assertThat(row.get("actor")).isEqualTo("kenji");
        });

        // The membership window filters at resolution, exactly like a role assignment's.
        GroupAdmin.removeMember(identity, MANAGED, "kenji", "SALES", "u1");
        GroupAdmin.addMember(identity, MANAGED, "kenji", "SALES", "u1", "2020-01-01",
                "2020-06-01");
        Principal expired = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(expired.groups()).doesNotContain("SALES");
        assertThat(expired.roles()).doesNotContain("grp.reader");

        // A membership that names nothing is refused rather than silently doing nothing.
        assertThatThrownBy(() -> GroupAdmin.addMember(identity, MANAGED, "kenji", "NOPE",
                "u1", "", "")).isInstanceOf(TqlException.class).hasMessageContaining("No group");
        assertThatThrownBy(() -> GroupAdmin.grantRole(identity, MANAGED, "SALES",
                "no.such.role")).isInstanceOf(TqlException.class)
                .hasMessageContaining("to bundle");

        // Deleting empties the joins first, and records a leave for whoever was in it.
        GroupAdmin.deleteGroup(identity, MANAGED, "kenji", "SALES");
        assertThat(historyFor("SALES")).anySatisfy(
                row -> assertThat(row.get("change_kind")).isEqualTo(GrantHistory.GROUP_LEFT));
        assertThat(identity.execute(MANAGED, IdentityContracts.LIST_GROUPS, Map.of()))
                .noneSatisfy(row -> assertThat(row.get("group_code")).isEqualTo("SALES"));
    }

    /**
     * Slice 5 of docs/access-governance.md: a campaign snapshots, decisions are recorded
     * against the snapshot, and closing executes the revokes through the ordinary write.
     */
    @Test
    void aReviewSnapshotsDecidesAndExecutesOnClose() {
        RoleAdmin.createRole(identity, MANAGED, "rev.keep", "残す", "");
        RoleAdmin.createRole(identity, MANAGED, "rev.drop", "外す", "");
        RoleAdmin.createRole(identity, MANAGED, "rev.gone", "消える", "");
        RoleAdmin.assignRole(identity, MANAGED, "kenji", "u1", "rev.keep", "", "");
        RoleAdmin.assignRole(identity, MANAGED, "kenji", "u1", "rev.drop", "", "");
        RoleAdmin.assignRole(identity, MANAGED, "kenji", "u1", "rev.gone", "", "");

        String reviewId = String.valueOf(AccessReview.open(identity, MANAGED, "kenji",
                "Q3 review", "").get("opened"));
        assertThat(AccessReview.reviewModel(identity, MANAGED, reviewId).get("items"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .anySatisfy(row -> assertThat(((Map<?, ?>) row).get("subject_code"))
                        .isEqualTo("rev.keep"));

        AccessReview.decide(identity, MANAGED, "kenji", reviewId, "u1", "role", "rev.keep",
                AccessReview.KEEP, "still needed");
        AccessReview.decide(identity, MANAGED, "kenji", reviewId, "u1", "role", "rev.drop",
                AccessReview.REVOKE, "left the team");
        AccessReview.decide(identity, MANAGED, "kenji", reviewId, "u1", "role", "rev.gone",
                AccessReview.REVOKE, "left the team");
        assertThatThrownBy(() -> AccessReview.decide(identity, MANAGED, "kenji", reviewId,
                "u1", "role", "rev.keep", "maybe", null))
                .isInstanceOf(TqlException.class).hasMessageContaining("A decision is");

        // The gap between snapshot and close is visible: this grant went away meanwhile.
        RoleAdmin.unassignRole(identity, MANAGED, "kenji", "u1", "rev.gone");

        Map<String, Object> closed = AccessReview.close(identity, MANAGED, "kenji", reviewId);
        assertThat(closed).containsEntry("revoked", 1).containsEntry("stale", 1);
        Principal after = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(after.roles()).contains("rev.keep").doesNotContain("rev.drop", "rev.gone");

        // The revocation is attributed to the campaign, not to a plain administrative edit.
        assertThat(historyFor("rev.drop")).anySatisfy(row -> {
            assertThat(row.get("source")).isEqualTo(AccessReview.SOURCE);
            assertThat(row.get("correlation")).isEqualTo(reviewId);
        });

        // A closed campaign is the record of what was certified; it takes no more decisions.
        assertThatThrownBy(() -> AccessReview.decide(identity, MANAGED, "kenji", reviewId,
                "u1", "role", "rev.keep", AccessReview.KEEP, null))
                .isInstanceOf(TqlException.class).hasMessageContaining("No open review item");
        assertThatThrownBy(() -> AccessReview.close(identity, MANAGED, "kenji", reviewId))
                .isInstanceOf(TqlException.class).hasMessageContaining("No open review");

        RoleAdmin.unassignRole(identity, MANAGED, "kenji", "u1", "rev.keep");
    }

    /**
     * Slice 6 of docs/access-governance.md: an unowned role cannot be asked for, an
     * approval lands the grant through the ordinary write, and a decided request is final.
     */
    @Test
    void anAccessRequestNeedsAnOwnerAndItsApprovalLandsTheGrant() {
        RoleAdmin.createRole(identity, MANAGED, "req.unowned", "誰のものでもない", "");
        RoleAdmin.createRole(identity, MANAGED, "req.owned", "持ち主あり", "");

        // Deny by default: with no owner there is nobody to approve, so nothing to ask.
        assertThatThrownBy(() -> AccessRequests.request(identity, MANAGED, "u1",
                "req.unowned", "please", null))
                .isInstanceOf(TqlException.class).hasMessageContaining("no owner");
        assertThatThrownBy(() -> AccessRequests.addOwner(identity, MANAGED, "no.such.role",
                AccessRequests.OWNER_USER, "kenji"))
                .isInstanceOf(TqlException.class).hasMessageContaining("No role");

        AccessRequests.addOwner(identity, MANAGED, "req.owned", AccessRequests.OWNER_USER,
                "kenji");
        assertThat(AccessRequests.myRequestsModel(identity, MANAGED, "u1").get("requestable"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .anySatisfy(row -> assertThat(((Map<?, ?>) row).get("role_code"))
                        .isEqualTo("req.owned"));

        AccessRequests.request(identity, MANAGED, "u1", "req.owned", "covering a release",
                "30");
        Map<String, Object> pending = onlyRequest("u1", "req.owned");
        assertThat(pending).containsEntry("status", AccessRequests.PENDING);

        String requestId = String.valueOf(pending.get("request_id"));
        AccessRequests.decide(identity, MANAGED, "kenji", requestId,
                AccessRequests.APPROVED, "ok");
        assertThat(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow().roles())
                .contains("req.owned");
        // Time-boxed by the duration asked for, and attributed to the request.
        assertThat(historyFor("req.owned")).anySatisfy(row -> {
            assertThat(row.get("source")).isEqualTo(AccessRequests.SOURCE);
            assertThat(row.get("correlation")).isEqualTo(requestId);
            assertThat(row.get("ends_at")).isNotNull();
        });

        // A decided request is final: a second approver's click grants nothing twice.
        assertThatThrownBy(() -> AccessRequests.decide(identity, MANAGED, "hana", requestId,
                AccessRequests.APPROVED, null))
                .isInstanceOf(TqlException.class).hasMessageContaining("already been decided");

        RoleAdmin.unassignRole(identity, MANAGED, "kenji", "u1", "req.owned");
    }

    /** Ownership by group: anybody in the owning group sees the request in their queue. */
    @Test
    void aGroupOwnerSeesTheQueueAndAStrangerDoesNot() {
        RoleAdmin.createRole(identity, MANAGED, "req.bygroup", "班", "");
        AccessRequests.addOwner(identity, MANAGED, "req.bygroup", AccessRequests.OWNER_GROUP,
                "OWNERS");
        AccessRequests.request(identity, MANAGED, "u1", "req.bygroup", "please", null);

        assertThat(AccessRequests.queueModel(identity, MANAGED, "someone",
                java.util.List.of("OWNERS")).get("rows"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .anySatisfy(row -> assertThat(((Map<?, ?>) row).get("role_code"))
                        .isEqualTo("req.bygroup"));
        assertThat(AccessRequests.queueModel(identity, MANAGED, "someone",
                java.util.List.of("OTHERS")).get("rows"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .noneSatisfy(row -> assertThat(((Map<?, ?>) row).get("role_code"))
                        .isEqualTo("req.bygroup"));

        AccessRequests.decide(identity, MANAGED, "hana",
                String.valueOf(onlyRequest("u1", "req.bygroup").get("request_id")),
                AccessRequests.REJECTED, "not now");
        // A rejection changes nothing held, so it leaves no grant row — only the request.
        assertThat(historyFor("req.bygroup")).isEmpty();
    }

    private Map<String, Object> onlyRequest(String userId, String roleCode) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("requesterId", userId);
        params.put("status", null);
        for (Map<String, Object> row : identity.execute(MANAGED,
                IdentityContracts.LIST_ACCESS_REQUESTS, params)) {
            if (roleCode.equals(String.valueOf(row.get("role_code")))) {
                return row;
            }
        }
        throw new IllegalStateException("No request for " + roleCode);
    }

    /** A constraint over a role code nothing names cannot fire, so it is refused. */
    @Test
    void aConstraintOverAnUnknownRoleIsRefused() {
        assertThatThrownBy(() -> RoleAdmin.createConstraint(identity, MANAGED, "Nope",
                SeparationOfDuties.BLOCK, "orders.approver", "no.such.role"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("No role 'no.such.role'");
    }

    private static String constraintId(String name) {
        for (SeparationOfDuties.Constraint constraint : SeparationOfDuties.load(identity,
                MANAGED)) {
            if (name.equals(constraint.name())) {
                return constraint.id();
            }
        }
        throw new IllegalStateException("No constraint named " + name);
    }

    private java.util.List<Map<String, Object>> historyFor(String code) {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> row : identity.execute(MANAGED,
                IdentityContracts.LIST_GRANT_HISTORY, params("u1", null))) {
            if (code.equals(row.get("subject_code"))) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> params(String userId, String application) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("userId", userId);
        params.put("application", application);
        params.put("since", null);
        return params;
    }

    @Test
    void adminAssignmentWithAWindowRoundTrips() {
        RoleAdmin.assignRole(identity, MANAGED, "admin", "u1", "orders.approver",
                "2020-01-01T00:00", "2020-06-01T00:00");
        Principal expiredNow = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(expiredNow.roles()).doesNotContain("orders.approver");

        RoleAdmin.assignRole(identity, MANAGED, "admin", "u1", "orders.approver", "", "");
        Principal back = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(back.roles()).contains("orders.approver");
    }
}
