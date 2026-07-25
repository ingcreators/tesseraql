package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmbientBindsTest {

    private static EvaluationContext context(Object principal) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (principal != null) {
            root.put("principal", principal);
        }
        return new EvaluationContext(root);
    }

    /**
     * The property shape of the security module's principal, without the dependency (the
     * resolver reads maps and accessors identically; a test-local record would be reflectively
     * inaccessible across packages).
     */
    private static final Map<String, Object> PRINCIPAL = Map.of(
            "subject", "sub-1",
            "loginId", "ichimura",
            "tenantId", "t-demo",
            "roles", List.of("INV_WRITE"),
            "permissions", List.of("inv:write"),
            "groups", List.of("warehouse"),
            "claims", Map.of("secret_claim", "hidden"));

    /**
     * The documented failure mode, which was not the real one.
     *
     * <p>The class javadoc promised that a {@code principal.*} bind on a request with no
     * principal "fails loudly as an unbound parameter instead of binding null". It bound null: a
     * missing path segment evaluates to null like any other, so the statement ran with
     * {@code = NULL} — a predicate matching nothing, or matching the rows whose column is unset.
     * A quiet wrong answer where the documentation promised a loud failure.
     */
    @Test
    void aPrincipalBindWithNoPrincipalFails() {
        List<SqlNode> nodes = Sql2WayParser.parse(
                "select * from orders where owner = /* principal.loginId */'x'");

        assertThatThrownBy(() -> SqlRenderer.render(nodes, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2112")
                .hasMessageContaining("principal.loginId");
    }

    @Test
    void aSeededPrincipalFieldThatIsNullStaysNull() {
        // A fact about the principal, not the absence of one: a caller with no tenant binds
        // null exactly as before, and only the missing namespace is an error.
        Map<String, Object> withoutTenant = new LinkedHashMap<>(PRINCIPAL);
        withoutTenant.put("tenantId", null);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("principal", withoutTenant);

        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(
                "select * from orders where tenant = /* principal.tenantId */'t'"), params);

        assertThat(bound.parameters()).hasSize(1);
        assertThat(bound.parameters().get(0).value()).isNull();
    }

    @Test
    void theNamespaceIsExactlyTheCuratedFieldSet() {
        Map<String, Object> binds = AmbientBinds.principal(context(PRINCIPAL));

        assertThat(binds).containsOnlyKeys("subject", "loginId", "tenantId", "roles",
                "permissions", "groups");
        assertThat(binds)
                .containsEntry("loginId", "ichimura")
                .containsEntry("tenantId", "t-demo")
                .containsEntry("roles", List.of("INV_WRITE"));
        // Closed namespace: raw claims never ride along.
        assertThat(binds.values()).noneMatch(v -> String.valueOf(v).contains("hidden"));
    }

    @Test
    void seedingNeverOverridesADeclaredParameter() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("principal", "explicit");

        AmbientBinds.seed(params, context(PRINCIPAL));

        assertThat(params).containsEntry("principal", "explicit");
    }

    @Test
    void withoutAPrincipalNothingIsSeeded() {
        Map<String, Object> params = new LinkedHashMap<>();

        AmbientBinds.seed(params, context(null));

        // A principal.* bind on a public route then fails loudly as an unbound parameter
        // instead of binding null.
        assertThat(params).isEmpty();
        assertThat(AmbientBinds.principal(context(null))).isNull();
    }

    @Test
    void seededBindsRenderInTwoWaySql() {
        Map<String, Object> params = new LinkedHashMap<>();
        AmbientBinds.seed(params, context(PRINCIPAL));

        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(
                "update products set updated_by = /* principal.loginId */'someone'"
                        + " where tenant_id = /* principal.tenantId */'t'"),
                params);

        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly("ichimura", "t-demo");
    }
}
