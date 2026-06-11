package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.SqlBinding;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Rebinds the SQL parameters for one additional named query of a route (the {@code queries:} block,
 * design ch. 6.3). It runs after the main query, so its source expressions can reference earlier
 * results (for example {@code sql.rows}) as well as the request context ({@code path.id},
 * {@code query.q}, {@code principal.*}).
 */
public final class NamedQueryBinder implements Processor {

    private final SqlBinding binding;

    public NamedQueryBinder(SqlBinding binding) {
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
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        exchange.setProperty(TesseraqlProperties.SQL_PARAMS, params);
    }
}
