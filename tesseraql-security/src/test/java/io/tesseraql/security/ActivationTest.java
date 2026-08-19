package io.tesseraql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The active-view derivation (docs/application-roles.md structural decision 4): reachability
 * reads the union, conduct reads the active view — stack-wide roles plus the one activated
 * member role, permissions recomputed from the active bundles plus the direct grants.
 */
class ActivationTest {

    private static final Principal KENJI = new Principal("u-1", "kenji", "Kenji", null,
            List.of(), List.of("keiri", "orders.approver", "billing.viewer"),
            List.of("tql.app.use.orders", "orders.approve", "billing.view"),
            Map.of("locale", "ja"),
            List.of(new Principal.RoleGrant("keiri", null, List.of("tql.app.use.orders")),
                    new Principal.RoleGrant("orders.approver", "orders",
                            List.of("orders.approve")),
                    new Principal.RoleGrant("billing.viewer", "billing",
                            List.of("billing.view"))),
            List.of("direct.grant"));

    @Test
    void activatingOneRoleKeepsStackWideDropsEveryOtherApplicationRole() {
        Principal active = Activation.activate(KENJI, "orders", "orders.approver");
        assertThat(active.roles()).containsExactly("keiri", "orders.approver");
        assertThat(active.permissions())
                .contains("tql.app.use.orders", "orders.approve", "direct.grant")
                .doesNotContain("billing.view");
        assertThat(active.claims().get(Activation.ACTING_ROLE_CLAIM))
                .isEqualTo("orders.approver");
        assertThat(Activation.actingRole(active)).isEqualTo("orders.approver");
        // The attribution survives the swap — it is what lists the other capacities.
        assertThat(active.roleGrants()).hasSize(3);
    }

    @Test
    void activatingNothingLeavesOnlyTheStackWideRolesActive() {
        Principal active = Activation.activate(KENJI, "orders", null);
        assertThat(active.roles()).containsExactly("keiri");
        assertThat(active.permissions())
                .contains("tql.app.use.orders", "direct.grant")
                .doesNotContain("orders.approve", "billing.view");
        assertThat(active.claims()).doesNotContainKey(Activation.ACTING_ROLE_CLAIM);
    }

    /** A pre-upgrade session or claim-asserted bearer: no attribution, the union stays. */
    @Test
    void emptyGrantsPassThroughUntouched() {
        Principal legacy = new Principal("u-2", "old", "Old", null, List.of(),
                List.of("ADMIN"), List.of("everything"), Map.of());
        assertThat(Activation.activate(legacy, "orders", "ADMIN")).isSameAs(legacy);
        assertThat(Activation.actingRole(legacy)).isNull();
    }

    @Test
    void grantsForScopesToOneApplication() {
        assertThat(Activation.grantsFor(KENJI, "orders"))
                .extracting(Principal.RoleGrant::role)
                .containsExactly("orders.approver");
        assertThat(Activation.grantsFor(KENJI, "unknown")).isEmpty();
    }
}
