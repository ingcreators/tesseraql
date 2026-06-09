package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.InputPolicy;
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
 * <p>It parses the JSON body, enforces the mass-assignment guard (unknown and non-writable fields,
 * design ch. 33.1/33.2), validates and coerces declared inputs, builds the
 * {@code query}/{@code body}/{@code params} context, publishes the authenticated principal, and
 * resolves the route's {@code sql.params} source expressions into bind values.
 */
public final class RequestBinder implements Processor {

    private static final TqlErrorCode FIELD_REJECTED = new TqlErrorCode(TqlDomain.FIELD, 2002);
    private static final System.Logger LOG = System.getLogger(RequestBinder.class.getName());

    private final RouteDefinition route;
    private final ObjectMapper mapper = new ObjectMapper();

    public RequestBinder(RouteDefinition route) {
        this.route = route;
    }

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> body = parseBody(exchange);
        guardMassAssignment(body);

        Map<String, Object> effective = InputBinder.bind(route.input(),
                name -> rawValue(name, body, exchange));

        Map<String, Object> context = new HashMap<>();
        context.put("query", effective);
        context.put("params", effective);
        context.put("body", body);
        context.put("principal", exchange.getProperty(TesseraqlProperties.PRINCIPAL));

        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        exchange.setProperty(TesseraqlProperties.SQL_PARAMS, resolveSqlParams(context));
    }

    private Map<String, Object> parseBody(Exchange exchange) {
        String raw = exchange.getMessage().getBody(String.class);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(raw, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(FIELD_REJECTED, "Request body is not valid JSON");
        }
    }

    private void guardMassAssignment(Map<String, Object> body) {
        if (body.isEmpty()) {
            return;
        }
        InputPolicy policy = route.effectiveInputPolicy();
        for (String key : body.keySet()) {
            InputField field = route.input().get(key);
            if (field == null) {
                if (policy.rejectsUnknownFields()) {
                    throw new TqlException(FIELD_REJECTED, "Unknown input field '" + key + "'");
                }
                continue;
            }
            if (!field.isWritable()) {
                switch (policy.readOnlyBehaviorOrDefault()) {
                    case "ignore" -> { /* drop silently */ }
                    case "warn" -> LOG.log(System.Logger.Level.WARNING,
                            "Ignoring non-writable input field ''{0}''", key);
                    default -> throw new TqlException(FIELD_REJECTED,
                            "Field '" + key + "' is not writable");
                }
            }
        }
    }

    private String rawValue(String name, Map<String, Object> body, Exchange exchange) {
        if (body.containsKey(name) && body.get(name) != null) {
            return String.valueOf(body.get(name));
        }
        return exchange.getMessage().getHeader(name, String.class);
    }

    private Map<String, Object> resolveSqlParams(Map<String, Object> context) {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> sqlParams = new LinkedHashMap<>();
        if (route.sql() != null) {
            route.sql().params().forEach((bindName, sourceExpr) ->
                    sqlParams.put(bindName, evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        }
        return sqlParams;
    }
}
