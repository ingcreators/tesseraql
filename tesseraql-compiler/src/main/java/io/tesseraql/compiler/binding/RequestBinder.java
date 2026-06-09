package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Camel processor that binds an HTTP request into the TesseraQL execution context (design ch. 7.2,
 * the {@code tesseraqlHttpRequestBinder}).
 *
 * <p>It validates and coerces declared inputs, builds the {@code query}/{@code params} context, and
 * resolves the route's {@code sql.params} source expressions into concrete bind values for the
 * {@code tesseraql-sql} component.
 */
public final class RequestBinder implements Processor {

    private final RouteDefinition route;

    public RequestBinder(RouteDefinition route) {
        this.route = route;
    }

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> effective = InputBinder.bind(route.input(),
                name -> exchange.getMessage().getHeader(name, String.class));

        Map<String, Object> context = new HashMap<>();
        context.put("query", effective);
        context.put("params", effective);
        context.put("principal", exchange.getProperty(TesseraqlProperties.PRINCIPAL));

        Map<String, Object> sqlParams = resolveSqlParams(context);

        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        exchange.setProperty(TesseraqlProperties.SQL_PARAMS, sqlParams);
    }

    private Map<String, Object> resolveSqlParams(Map<String, Object> context) {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> sqlParams = new LinkedHashMap<>();
        if (route.sql() != null) {
            route.sql().params().forEach((bindName, sourceExpr) ->
                    sqlParams.put(bindName, evaluation.resolve(path(sourceExpr))));
        }
        return sqlParams;
    }

    private static java.util.List<String> path(String dotted) {
        return Arrays.asList(dotted.split("\\."));
    }
}
