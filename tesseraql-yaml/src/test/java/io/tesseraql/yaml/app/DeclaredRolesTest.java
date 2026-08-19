package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeclaredRolesTest {

    @Test
    void aValidDeclarationParsesAndPasses() {
        List<DeclaredRoles.DeclaredRole> roles = DeclaredRoles.parse(List.of(
                Map.of("code", "orders.approver", "name", "承認者",
                        "permissions", List.of("orders.approve", "orders.read")),
                Map.of("code", "orders.viewer")));
        assertThat(roles).hasSize(2);
        assertThat(DeclaredRoles.violations("orders", roles)).isEmpty();
    }

    @Test
    void theFenceRefusesWhatItMust() {
        List<DeclaredRoles.DeclaredRole> roles = DeclaredRoles.parse(List.of(
                Map.of("code", "approver"),
                Map.of("code", "tql.ops.view.orders"),
                Map.of("code", "orders.ok", "permissions", List.of("billing.read")),
                Map.of("code", "orders.ok")));
        List<String> violations = DeclaredRoles.violations("orders", roles);
        assertThat(violations).hasSize(4);
        assertThat(violations.get(0)).contains("does not begin with this application's own name");
        assertThat(violations.get(1)).contains("framework's own mark");
        assertThat(violations.get(2)).contains("billing.read");
        assertThat(violations.get(3)).contains("declared more than once");
    }

    @Test
    void theBootBackstopThrowsTheFirstViolation() {
        assertThatThrownBy(() -> DeclaredRoles.require("orders",
                List.of(Map.of("code", "approver"))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("does not begin");
        assertThat(DeclaredRoles.require("orders", null)).isEmpty();
        assertThat(DeclaredRoles.require(null, List.of(Map.of("code", "x")))).hasSize(1);
    }

    @Test
    void aNonAsciiRoleCodeIsLegal() {
        assertThat(DeclaredRoles.violations("受注管理", DeclaredRoles.parse(List.of(
                Map.of("code", "受注管理.承認者"))))).isEmpty();
    }
}
