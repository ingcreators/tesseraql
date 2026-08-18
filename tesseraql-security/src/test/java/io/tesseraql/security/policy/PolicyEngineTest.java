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
