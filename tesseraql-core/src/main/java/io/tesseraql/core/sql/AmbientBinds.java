package io.tesseraql.core.sql;

import io.tesseraql.core.expr.EvaluationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ambient SQL bind namespaces (docs/ambient-params.md): values resolved from the request
 * context and seeded under every statement's parameters without per-route {@code params:}
 * wiring. {@code audit.user}/{@code audit.now} and the multi-tenancy {@code tenant.id} bind came
 * first; {@code principal.*} generalizes the precedent:
 *
 * <pre>{@code
 * UPDATE products
 * SET    updated_by = /* principal.loginId *}{@code /'someone'
 * WHERE  tenant_id  = /* principal.tenantId *}{@code /'t-demo'
 * }</pre>
 *
 * <p>The namespace is <b>closed and read-only</b>: exactly the fields below resolve, there is no
 * expression evaluation inside a bind comment, and no raw-claim passthrough — a claim an app
 * needs goes through explicit {@code params:} wiring where it is visible and reviewable. A
 * route-declared parameter named {@code principal} shadows the ambient map entirely (explicit
 * always wins). Without an authenticated principal nothing is seeded, so a {@code principal.*}
 * bind on a public route fails ({@code TQL-SQL-2112}) instead of binding null. Only the absence
 * of the whole namespace is an error; a seeded field that is genuinely null stays null.
 */
public final class AmbientBinds {

    /** The parameter-map key the curated principal fields are seeded under. */
    public static final String PRINCIPAL = "principal";

    /** The closed field set; each resolves via the same read-only property access as {@code params:}. */
    private static final List<String> PRINCIPAL_FIELDS = List.of("subject", "loginId", "tenantId",
            "roles", "permissions", "groups");

    /** The audit binds every command seeds; they are ambient exactly as {@code principal.*} is. */
    private static final List<String> AUDIT_BINDS = List.of("audit.user", "audit.now");

    private AmbientBinds() {
    }

    /**
     * Whether a bind expression names something the framework supplies rather than something a
     * route or a rule must wire. Callers that check a declared bind contract need the same
     * answer as the seeding above, so they ask here instead of restating the field list — a
     * second copy would quietly disagree the first time a field is added.
     */
    public static boolean isAmbient(String bindExpression) {
        String expression = bindExpression == null ? "" : bindExpression.trim();
        if (AUDIT_BINDS.contains(expression)) {
            return true;
        }
        return PRINCIPAL_FIELDS.stream()
                .anyMatch(field -> expression.equals(PRINCIPAL + "." + field));
    }

    /**
     * The curated {@code principal.*} bind map resolved from the context's authenticated
     * principal, or {@code null} when the request has none (public routes seed nothing).
     */
    public static Map<String, Object> principal(EvaluationContext evaluation) {
        if (evaluation.resolve(List.of(PRINCIPAL)) == null) {
            return null;
        }
        Map<String, Object> binds = new LinkedHashMap<>();
        for (String field : PRINCIPAL_FIELDS) {
            binds.put(field, evaluation.resolve(List.of(PRINCIPAL, field)));
        }
        return binds;
    }

    /**
     * Seeds the ambient namespaces under {@code params} without overriding anything declared:
     * a route-local {@code params:} entry named {@code principal} wins.
     */
    public static void seed(Map<String, Object> params, EvaluationContext evaluation) {
        if (params.containsKey(PRINCIPAL)) {
            return;
        }
        Map<String, Object> principal = principal(evaluation);
        if (principal != null) {
            params.put(PRINCIPAL, principal);
        }
    }
}
