package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.ResponseSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Camel processor that renders the JSON response from the response template (design ch. 7.2, the
 * {@code tesseraqlJsonResponseRenderer}).
 *
 * <p>The response body template is walked recursively; leaf strings are treated as context
 * expressions (for example {@code sql.rows}, {@code params.limit}) resolved against the execution
 * context, then the resulting tree is serialized to JSON.
 */
public final class JsonResponseRenderer implements Processor {

    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.CAMEL, 3001);

    private final ResponseSpec.JsonResponse response;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonResponseRenderer(ResponseSpec.JsonResponse response) {
        this.response = response;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        Object body = resolve(response.body(), evaluation);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(RENDER_ERROR, "Failed to serialize JSON response: " + ex.getMessage());
        }

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, response.effectiveStatus());
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(json);
    }

    private Object resolve(Object template, EvaluationContext evaluation) {
        return switch (template) {
            case null -> null;
            case Map<?, ?> map -> {
                Map<String, Object> resolved = new LinkedHashMap<>();
                map.forEach((key, value) -> resolved.put(String.valueOf(key), resolve(value, evaluation)));
                yield resolved;
            }
            case List<?> list -> {
                List<Object> resolved = new ArrayList<>(list.size());
                list.forEach(element -> resolved.add(resolve(element, evaluation)));
                yield resolved;
            }
            case String expression -> evaluation.resolve(Arrays.asList(expression.split("\\.")));
            default -> template;
        };
    }
}
