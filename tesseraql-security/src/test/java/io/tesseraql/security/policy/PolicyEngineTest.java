package io.tesseraql.security.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static final Policy USERS_READ = new Policy("users.read",
            List.of(Policy.Rule.ofRole("USER_READ"), Policy.Rule.ofPermission("users:read")));

    private static PolicyEngine engine() {
        return new PolicyEngine(new SecurityConfig(Map.of("users.read", USERS_READ), null));
    }

    private static Principal principal(List<String> roles, List<String> permissions) {
        return new Principal("u001", "sato", "Sato", "tenant-a",
                List.of(), roles, permissions, Map.of());
    }

    @Test
    void permitsWhenAnyRuleMatches() {
        assertThat(engine().permits("users.read", principal(List.of("USER_READ"), List.of())))
                .isTrue();
        assertThat(engine().permits("users.read", principal(List.of(), List.of("users:read"))))
                .isTrue();
    }

    @Test
    void deniesWhenNoRuleMatches() {
        assertThatThrownBy(
                () -> engine().authorize("users.read", principal(List.of("OTHER"), List.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4031");
    }

    @Test
    void unauthorizedWhenNoPrincipal() {
        assertThatThrownBy(() -> engine().authorize("users.read", null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4011");
    }

    @Test
    void deniesUndefinedPolicyByDefault() {
        assertThatThrownBy(
                () -> engine().authorize("unknown.policy", principal(List.of("ADMIN"), List.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4031");
    }

    /**
     * A policy id under the framework's {@code tql.} mark is the atom itself, synthesized with
     * no declaration behind it (docs/stack-shells.md structural decision 1): it permits exactly
     * the principals granted that permission code — or the family's terminal wildcard — and
     * roles never satisfy it, because a framework surface checks atoms, never roles.
     */
    @Test
    void aMarkedPolicyIdIsTheSynthesizedAtomCheck() {
        assertThat(engine().permits("tql.iam.admin.view",
                principal(List.of(), List.of("tql.iam.admin.view")))).isTrue();
        assertThat(engine().permits("tql.iam.admin.view",
                principal(List.of("ADMIN"), List.of()))).isFalse();
    }

    @Test
    void theSynthesizedAtomHonoursTheFamilysTerminalWildcard() {
        assertThat(engine().permits("tql.app.use.orders",
                principal(List.of(), List.of("tql.app.use.*")))).isTrue();
        assertThat(engine().permits("tql.app.use.orders",
                principal(List.of(), List.of("tql.app.use.billing")))).isFalse();
    }

    /**
     * A per-application atom is satisfied by the store-wide grant it narrows
     * (docs/access-governance.md structural decision 7), so a route checking
     * {@code tql.iam.write.orders} admits the store-wide administrator without saying so.
     */
    @Test
    void theSynthesizedAtomHonoursTheStoreWideGrantItNarrows() {
        assertThat(engine().permits("tql.iam.write.orders",
                principal(List.of(), List.of("tql.iam.admin.write")))).isTrue();
        assertThat(engine().permits("tql.iam.view.orders",
                principal(List.of(), List.of("tql.iam.admin.view")))).isTrue();
        assertThat(engine().permits("tql.iam.write.orders",
                principal(List.of(), List.of("tql.iam.write.orders")))).isTrue();
        assertThat(engine().permits("tql.iam.write.orders",
                principal(List.of(), List.of("tql.iam.write.*")))).isTrue();
    }

    /**
     * Narrowing runs one way and per verb. Sight of the store is not authority over it, the
     * store-wide write atom is not sight, and another application's delegation is neither.
     */
    @Test
    void narrowingDoesNotLeakAcrossVerbsOrApplications() {
        assertThat(engine().permits("tql.iam.write.orders",
                principal(List.of(), List.of("tql.iam.admin.view")))).isFalse();
        assertThat(engine().permits("tql.iam.view.orders",
                principal(List.of(), List.of("tql.iam.admin.write")))).isFalse();
        assertThat(engine().permits("tql.iam.write.orders",
                principal(List.of(), List.of("tql.iam.write.billing")))).isFalse();
        // The store-wide atoms are exact strings, not the head of a per-application family:
        // holding a delegation must never satisfy the store-wide check.
        assertThat(engine().permits("tql.iam.admin.write",
                principal(List.of(), List.of("tql.iam.write.orders")))).isFalse();
        assertThat(engine().permits("tql.iam.admin.write",
                principal(List.of(), List.of("tql.iam.write.*")))).isFalse();
    }

    /** {@code tql.app.use.<name>} honours the exact grant and the wildcard, nothing looser. */
    @Test
    void appUseMatchesTheGrantOrTheWildcard() {
        assertThat(Atoms.appUse(List.of("tql.app.use.orders"), "orders")).isTrue();
        assertThat(Atoms.appUse(List.of("tql.app.use.*"), "orders")).isTrue();
        assertThat(Atoms.appUse(List.of("tql.app.use.orders"), "billing")).isFalse();
        assertThat(Atoms.appUse(List.of(), "orders")).isFalse();
        assertThat(Atoms.appUse(null, "orders")).isFalse();
    }
}
