package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.GrantConditions;
import io.tesseraql.security.Principal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Context conditions against a real store (docs/access-governance.md structural decision 8):
 * the write refuses what the evaluator could never satisfy, the condition rides the grant into
 * the principal, and a condition on a role reached through a group rides it too.
 */
@Testcontainers
class RoleConditionStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static IdentityService identity;
    static final RealmConfig MANAGED = RealmConfig.managed("main", "main");

    @BeforeAll
    static void seed() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
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
                    + " values ('r-app','orders.approver','Approver','orders')");
            s.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-group','keiri','Accounting')");
            s.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-plain','plain','Plain')");
            s.execute("insert into tql_permissions values ('orders.approve','orders.approve','x')");
            s.execute("insert into tql_role_permissions values ('r-app','orders.approve')");
            s.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r-app')");
            s.execute("insert into tql_user_roles (user_id, role_id) values ('u1','r-plain')");
            s.execute("insert into tql_groups (group_id, group_code, group_name)"
                    + " values ('g1','finance','Finance')");
            s.execute("insert into tql_user_groups (user_id, group_id) values ('u1','g1')");
            s.execute("insert into tql_group_roles values ('g1','r-group')");
        }
        RoleConditions.addCondition(identity, MANAGED, "orders.approver", "network",
                "10.0.0.0/8");
        RoleConditions.addCondition(identity, MANAGED, "orders.approver", "hours",
                "MON-FRI 09:00-18:00");
        RoleConditions.addCondition(identity, MANAGED, "keiri", "network", "192.168.0.0/16");
    }

    @Test
    void aConditionRidesTheGrantIntoThePrincipal() {
        Principal principal = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();

        assertThat(conditionsOf(principal, "orders.approver"))
                .containsExactlyInAnyOrder(
                        new Principal.RoleGrant.Condition("hours", "MON-FRI 09:00-18:00"),
                        new Principal.RoleGrant.Condition("network", "10.0.0.0/8"));
        assertThat(conditionsOf(principal, "keiri"))
                .as("a role reached through a group carries its conditions too")
                .containsExactly(new Principal.RoleGrant.Condition("network", "192.168.0.0/16"));
        assertThat(conditionsOf(principal, "plain")).isEmpty();
    }

    /** The store read and the evaluator, end to end: inside the office, then outside it. */
    @Test
    void theResolvedPrincipalNarrowsAgainstARequestContext() {
        Principal principal = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        ZonedDateTime wednesdayMorning = ZonedDateTime.of(2026, 8, 19, 10, 0, 0, 0,
                ZoneId.of("Asia/Tokyo"));

        assertThat(GrantConditions.narrow(principal, "10.1.2.3", wednesdayMorning).roles())
                .contains("orders.approver", "plain").doesNotContain("keiri");
        assertThat(GrantConditions.narrow(principal, "10.1.2.3", wednesdayMorning.withHour(3))
                .roles()).as("outside the hours, inside the network").doesNotContain(
                        "orders.approver");
        assertThat(GrantConditions.narrow(principal, "192.168.1.1", wednesdayMorning)
                .permissions()).as("the approver's bundle leaves with the approver role")
                .doesNotContain("orders.approve");
    }

    @Test
    void aValueTheEvaluatorCouldNeverSatisfyIsRefusedAtTheWrite() {
        assertThatThrownBy(() -> RoleConditions.addCondition(identity, MANAGED, "plain",
                "network", "10.0.0.0/99"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4033");
        assertThatThrownBy(() -> RoleConditions.addCondition(identity, MANAGED, "plain",
                "hours", "whenever"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4033");
        assertThatThrownBy(() -> RoleConditions.addCondition(identity, MANAGED, "plain",
                "device", "managed"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4033");
    }

    /** A condition on a role that does not exist would narrow nothing while looking like it did. */
    @Test
    void aConditionOnAnUnknownRoleIsRefusedRatherThanWrittenNowhere() {
        assertThatThrownBy(() -> RoleConditions.addCondition(identity, MANAGED, "no.such.role",
                "network", "10.0.0.0/8"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4033");
    }

    @Test
    void removingTheLastConditionWidensTheRoleAgain() {
        RoleConditions.addCondition(identity, MANAGED, "plain", "network", "203.0.113.0/24");
        Principal narrowed = identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow();
        assertThat(conditionsOf(narrowed, "plain")).hasSize(1);

        RoleConditions.removeCondition(identity, MANAGED, "plain", "network", "203.0.113.0/24");

        assertThat(conditionsOf(identity.resolvePrincipal(MANAGED, "alice", null).orElseThrow(),
                "plain")).isEmpty();
        assertThatThrownBy(() -> RoleConditions.removeCondition(identity, MANAGED, "plain",
                "network", "203.0.113.0/24"))
                .as("removing what is not there is a refusal, not a silent no-op")
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4033");
    }

    @Test
    void theAdminModelListsEveryConditionWithItsRole() {
        Map<String, Object> model = RoleConditions.conditionsModel(identity, MANAGED);

        assertThat(model.get("available")).isEqualTo(1);
        assertThat(model.get("writable")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) model.get("rows");
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("role_code")).isEqualTo("orders.approver");
            assertThat(row.get("application")).isEqualTo("orders");
        });
    }

    private static List<Principal.RoleGrant.Condition> conditionsOf(Principal principal,
            String role) {
        return principal.roleGrants().stream().filter(grant -> role.equals(grant.role()))
                .findFirst().orElseThrow().conditions();
    }
}
