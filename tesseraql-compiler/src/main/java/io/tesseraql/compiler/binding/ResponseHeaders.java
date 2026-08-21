package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.pipeline.Exchange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A response's declared {@code headers:} block, compiled once and applied per request.
 *
 * <p>Shared by {@link HtmlResponseRenderer} and {@link JsonResponseRenderer} the way
 * {@link JsonResponseRenderer.CompiledStatus} already is: both response kinds carry the same block,
 * and a second copy of the guard-and-interpolate logic is how the two drift apart.
 *
 * <p>Two things happen to a declared header. A {@code {expression}} placeholder in its value
 * resolves against the execution context, so a header can carry per-request data. And a header
 * named in {@code headersWhen} is emitted only when its guard is truthy — on an HTML fragment that
 * is an {@code HX-Trigger} toast firing on success but not on a handled error; on a JSON response
 * it is the header defined in terms of a status that {@code statusWhen} already varies, such as
 * {@code Location} on a 201 or {@code Retry-After} on a 503.
 */
final class ResponseHeaders {

    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.CAMEL, 3001);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> declared;
    private final Map<String, Expr> guards;

    /**
     * Pre-compiles each header's optional guard, so a syntax error fails the build rather than a
     * request.
     */
    ResponseHeaders(Map<String, Object> declared, Map<String, String> headersWhen,
            ExpressionFunctions functions) {
        this.declared = declared == null ? Map.of() : Map.copyOf(declared);
        Map<String, Expr> compiled = new LinkedHashMap<>();
        if (headersWhen != null) {
            headersWhen.forEach((name, when) -> {
                if (when != null && !when.isBlank()) {
                    compiled.put(name, ExpressionParser.parse(when, functions));
                }
            });
        }
        this.guards = Map.copyOf(compiled);
    }

    /** Sets every header whose guard passes, resolving placeholders against this request. */
    void apply(Exchange exchange, EvaluationContext evaluation) {
        declared.forEach((name, value) -> {
            Expr guard = guards.get(name);
            if (guard != null && !guard.evalBoolean(evaluation)) {
                return;
            }
            try {
                // Resolve {expression} placeholders (recursively for a nested map/list) so a header
                // can carry per-request data; a value with no placeholder is unchanged. Nested
                // map/list values then serialize to JSON.
                Object resolved = Interpolation.interpolate(value, evaluation);
                String headerValue = resolved instanceof Map || resolved instanceof List
                        ? MAPPER.writeValueAsString(resolved)
                        : String.valueOf(resolved);
                exchange.getMessage().setHeader(name, headerValue);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new TqlException(RENDER_ERROR, "Failed to serialize header " + name);
            }
        });
    }

    boolean isEmpty() {
        return declared.isEmpty();
    }
}
