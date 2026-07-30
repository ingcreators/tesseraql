package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code decision.*} bind namespace (docs/decision-tables.md): seeded under every
 * statement's parameters like {@code principal.*}, and loud — never null — when a statement
 * names a decision its route never evaluated.
 */
class DecisionBindTest {

    private static final String SQL = "insert into requests (route) values"
            + " (/* decision.approvalRoute.route */'manager')";

    @Test
    void aSeededDecisionOutputBindsLikeAnyParameter() {
        BoundSql bound = SqlRenderer.render(SQL, Map.of(AmbientBinds.DECISION,
                Map.of("approvalRoute", Map.of("route", "director"))));

        assertThat(bound.parameters()).hasSize(1);
        assertThat(bound.parameters().get(0).value()).isEqualTo("director");
    }

    @Test
    void anUnevaluatedDecisionFailsLoudlyInsteadOfBindingNull() {
        assertThatThrownBy(() -> SqlRenderer.render(SQL, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4722");
        // The namespace being present does not excuse an alias that was never evaluated.
        assertThatThrownBy(() -> SqlRenderer.render(SQL,
                Map.of(AmbientBinds.DECISION, Map.of("other", Map.of("route", "x")))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4722");
    }

    @Test
    void aDirectiveBranchesOnADecisionOutput() {
        String sql = "update requests set status ="
                + " /*%if decision.approvalRoute.level == 0 */'approved'"
                + " /*%else*/'pending'/*%end*/ where id = /* id */1";
        Map<String, Object> params = Map.of("id", 7, AmbientBinds.DECISION,
                Map.of("approvalRoute", Map.of("level", 0)));

        assertThat(SqlRenderer.render(sql, params).sql()).contains("'approved'");
    }

    @Test
    void seedCarriesTheDecisionNamespaceWithoutOverridingDeclaredParams() {
        Map<String, Object> context = Map.of(AmbientBinds.DECISION,
                Map.of("approvalRoute", Map.of("route", "manager")));
        Map<String, Object> params = new HashMap<>();
        AmbientBinds.seed(params, new EvaluationContext(context));
        assertThat(params).containsKey(AmbientBinds.DECISION);

        Map<String, Object> declared = new HashMap<>(Map.of(AmbientBinds.DECISION, "explicit"));
        AmbientBinds.seed(declared, new EvaluationContext(context));
        assertThat(declared.get(AmbientBinds.DECISION)).isEqualTo("explicit");
    }
}
