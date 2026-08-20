package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Containment for delegated administration (docs/access-governance.md structural decision 7):
 * the delegated atom reaches its own application's access and nothing else.
 *
 * <p>The page that hides a button is a convenience. These are the refusals, so each boundary
 * the design names is asserted here rather than inferred from a rendered form.
 */
class AdminScopeTest {

    private static final List<String> MEMBERS = List.of("orders", "billing");

    private static AdminScope delegated(String... applications) {
        return AdminScope.forApplications(Set.of(applications));
    }

    @Test
    void theStoreWideWriteAtomIsUnscoped() {
        AdminScope scope = AdminScope.of(List.of("tql.iam.admin.write"), MEMBERS);
        assertThat(scope.isStoreWide()).isTrue();
        assertThat(scope.canWrite()).isTrue();
        scope.requireRole("platform.support", null);
        scope.requirePermission("tql.app.deploy.orders");
        scope.requireStoreWide("an assignment rule");
    }

    @Test
    void aDelegatedAtomNamesOneApplication() {
        AdminScope scope = AdminScope.of(List.of("tql.iam.write.orders"), MEMBERS);
        assertThat(scope.isStoreWide()).isFalse();
        assertThat(scope.applications()).containsExactly("orders");
    }

    /**
     * The terminal wildcard delegates every application — which is still not the stack-wide
     * roles or the framework's own atoms, so it is deliberately not read as store-wide.
     */
    @Test
    void theWildcardDelegatesEveryMemberButIsNotStoreWide() {
        AdminScope scope = AdminScope.of(List.of("tql.iam.write.*"), MEMBERS);
        assertThat(scope.isStoreWide()).isFalse();
        assertThat(scope.applications()).containsExactlyInAnyOrder("orders", "billing");
        assertThatThrownBy(() -> scope.requireRole("platform.support", null))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
    }

    @Test
    void aCallerWithNeitherAtomWritesNothing() {
        assertThat(AdminScope.of(List.of("tql.iam.admin.view"), MEMBERS).canWrite()).isFalse();
        assertThat(AdminScope.of(List.of(), MEMBERS).canWrite()).isFalse();
        assertThat(AdminScope.of(null, MEMBERS).canWrite()).isFalse();
    }

    /** A stack-wide role belongs to the deployment, never to any one application. */
    @Test
    void aStackWideRoleIsNeverOneApplications() {
        assertThatThrownBy(() -> delegated("orders").requireRole("support", null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-IAM-4036")
                .hasMessageContaining("stack-wide");
        assertThatThrownBy(() -> delegated("orders").requireRole("support", "  "))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
    }

    @Test
    void anotherApplicationsRoleIsRefused() {
        delegated("orders").requireRole("orders.approver", "orders");
        assertThatThrownBy(() -> delegated("orders").requireRole("billing.approver", "billing"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
    }

    @Test
    void aPermissionCodeMustCarryTheApplicationsName() {
        delegated("orders").requirePermission("orders.approve");
        assertThatThrownBy(() -> delegated("orders").requirePermission("billing.approve"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
        // A bare name that merely starts with the letters is not the application's namespace.
        assertThatThrownBy(() -> delegated("orders").requirePermission("ordersx.approve"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
    }

    /**
     * Delegating {@code orders} must not become a path to granting {@code tql.app.deploy.*} —
     * and not to granting the delegation atom itself, which would let a delegated
     * administrator widen their own reach.
     */
    @Test
    void noFrameworkAtomIsEverADelegatedAdministratorsToHandOut() {
        assertThatThrownBy(() -> delegated("orders").requirePermission("tql.app.deploy.orders"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
        assertThatThrownBy(() -> delegated("orders").requirePermission("tql.iam.write.orders"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
        assertThatThrownBy(() -> delegated("orders").requirePermission("tql.iam.admin.write"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
    }

    @Test
    void storeWideInstrumentsAreNotScoped() {
        assertThatThrownBy(() -> delegated("orders").requireStoreWide("an SoD constraint"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
    }

    /**
     * The per-application pages address one application, so a write arriving through one
     * belongs to it whoever the caller is — the store-wide administrator included.
     */
    @Test
    void confiningToTheAddressedApplicationNarrowsTheStoreWideAdministrator() {
        AdminScope confined = AdminScope.of(List.of("tql.iam.admin.write"), MEMBERS)
                .confinedTo("orders");
        assertThat(confined.isStoreWide()).isFalse();
        assertThat(confined.applications()).containsExactly("orders");
        confined.requireRole("orders.approver", "orders");
        assertThatThrownBy(() -> confined.requireRole("billing.approver", "billing"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
        assertThatThrownBy(() -> confined.requirePermission("tql.app.deploy.orders"))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-IAM-4036");
    }

    @Test
    void confiningNeverWidensADelegatedAdministrator() {
        assertThat(delegated("orders").confinedTo("billing").canWrite()).isFalse();
        assertThat(delegated("orders").confinedTo("orders").applications())
                .containsExactly("orders");
        assertThat(delegated("orders").confinedTo(null).canWrite()).isFalse();
        assertThat(delegated("orders").confinedTo("").canWrite()).isFalse();
    }
}
