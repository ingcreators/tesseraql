package io.tesseraql.compiler.binding;

import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.app.RequestSources;
import io.tesseraql.yaml.model.Binding;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rebinds the SQL parameters for one additional named query of a route (the {@code queries:} block,
 * design ch. 6.3). It runs after the main query, so its source expressions can reference earlier
 * results (for example {@code main.rows}) as well as the request context ({@code path.id},
 * {@code query.q}, {@code principal.*}).
 *
 * <p>A {@code service:} binding's {@code params:} may also read {@code header.<name>}
 * (docs/audit-low-leads.md slice 9): the wire header of that name, first value, matched
 * without regard to case — the stack shells forward the caller's session and CSRF token to
 * the member they delegate to. It is read here and nowhere else: not from the request
 * context, so a query parameter or a body field spelled {@code Cookie} cannot stand in for
 * the header on the delegated hop, and not on a statement's binding, which the compiler
 * refuses before this step exists.
 */
public final class NamedQueryBinder implements Step {

    private final Binding binding;

    public NamedQueryBinder(Binding binding) {
        this.binding = binding;
    }

    @Override
    public void process(Exchange exchange) {
        @SuppressWarnings("unchecked")
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        binding.params().forEach((bindName, sourceExpr) -> params.put(bindName,
                resolve(exchange, evaluation, sourceExpr)));
        // Ambient principal.* binds (docs/ambient-params.md); declared params win by name.
        io.tesseraql.core.sql.AmbientBinds.seed(params, evaluation);
        exchange.setProperty(TesseraqlProperties.SQL_PARAMS, params);
    }

    private Object resolve(Exchange exchange, EvaluationContext evaluation, String sourceExpr) {
        if (binding.isService()) {
            java.util.Optional<String> header = RequestSources.headerName(sourceExpr);
            if (header.isPresent()) {
                return exchange.request().header(header.get());
            }
        }
        return evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")));
    }
}
