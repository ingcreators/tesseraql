package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.yaml.model.ResponseSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline step that renders the JSON response from the response template (design ch. 7.2, the
 * {@code tesseraqlJsonResponseRenderer}).
 *
 * <p>The response body template is walked recursively; leaf strings are treated as context
 * expressions (for example {@code main.rows}, {@code params.limit}) resolved against the execution
 * context, then the resulting tree is serialized to JSON.
 */
public final class JsonResponseRenderer implements Step {

    private static final TqlErrorCode RENDER_ERROR = new TqlErrorCode(TqlDomain.ROUTE, 3001);

    private final ResponseSpec.JsonResponse response;
    private final Object compiledBody;
    private final java.util.List<CompiledStatus> statusWhen;
    private final ResponseHeaders headers;
    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();

    /** A pre-compiled statusWhen arm (roadmap Phase 41): first truthy condition wins. */
    record CompiledStatus(io.tesseraql.core.expr.Expr when, int status) {

        /**
         * The declared arms, pre-compiled so a syntax error fails the build. Shared with
         * {@link HtmlResponseRenderer}: both response kinds carry the same block.
         */
        static List<CompiledStatus> compileAll(
                List<ResponseSpec.StatusWhen> arms,
                io.tesseraql.core.expr.ExpressionFunctions functions) {
            return arms.stream()
                    .map(arm -> new CompiledStatus(
                            io.tesseraql.core.expr.ExpressionParser.parse(arm.when(), functions),
                            arm.status()))
                    .toList();
        }

        /**
         * The response status: the first arm whose condition is truthy against this request,
         * else the response's declared status.
         */
        static int resolve(List<CompiledStatus> arms, int declared,
                EvaluationContext evaluation) {
            for (CompiledStatus arm : arms) {
                if (arm.when().evalBoolean(evaluation)) {
                    return arm.status();
                }
            }
            return declared;
        }
    }

    public JsonResponseRenderer(ResponseSpec.JsonResponse response) {
        this(response, io.tesseraql.core.expr.ExpressionFunctions.processDefault());
    }

    /**
     * As {@link #JsonResponseRenderer(ResponseSpec.JsonResponse)}, resolving custom calls
     * against {@code functions}.
     */
    public JsonResponseRenderer(ResponseSpec.JsonResponse response,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        this.response = response;
        this.compiledBody = compile(response.body(), functions);
        this.statusWhen = CompiledStatus.compileAll(response.statusWhen(), functions);
        this.headers = new ResponseHeaders(response.headers(), response.headersWhen(),
                functions);
    }

    /**
     * Pre-compiles the body template's leaf strings as core-language expressions (roadmap
     * Phase 41): a plain dotted path parses identically to the legacy resolver, and computed
     * leaves ({@code params.qty * params.price}, {@code upper(...)}) come along for free. A
     * leaf the parser rejects falls back to legacy dotted-path resolution, so pre-Phase-41
     * bodies keep their exact behavior.
     */
    private static Object compile(Object template,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        return switch (template) {
            case null -> null;
            case Map<?, ?> map -> {
                Map<String, Object> compiled = new LinkedHashMap<>();
                map.forEach((key, value) -> compiled.put(String.valueOf(key),
                        compile(value, functions)));
                yield compiled;
            }
            case List<?> list -> {
                List<Object> compiled = new ArrayList<>(list.size());
                list.forEach(element -> compiled.add(compile(element, functions)));
                yield compiled;
            }
            case String expression -> {
                try {
                    yield io.tesseraql.core.expr.ExpressionParser.parse(expression, functions);
                } catch (RuntimeException ex) {
                    yield new io.tesseraql.core.expr.Expr.Path(
                            Arrays.asList(expression.split("\\.")));
                }
            }
            default -> template;
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(
                TesseraqlProperties.CONTEXT, Map.of(), Map.class);
        EvaluationContext evaluation = new EvaluationContext(context);

        Object body = resolve(compiledBody, evaluation);
        if (!response.fields().isEmpty()) {
            PolicyEngine policyEngine = exchange.beans().lookup(
                    TesseraqlProperties.POLICY_ENGINE_BEAN,
                    PolicyEngine.class);
            Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                    Principal.class);
            body = new FieldPolicyApplier(response.fields(), policyEngine, principal).apply(body);
        }
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(RENDER_ERROR,
                    "Failed to serialize JSON response: " + ex.getMessage());
        }

        int status = CompiledStatus.resolve(statusWhen, response.effectiveStatus(), evaluation);
        // Declared headers before the framework's own: Content-Type is this renderer's to set, and
        // a route naming it would be describing a body it is not producing.
        headers.apply(exchange, evaluation);
        exchange.response().status(status);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.setBody(json);
    }

    private Object resolve(Object template, EvaluationContext evaluation) {
        return switch (template) {
            case null -> null;
            case Map<?, ?> map -> {
                Map<String, Object> resolved = new LinkedHashMap<>();
                map.forEach((key, value) -> resolved.put(String.valueOf(key),
                        resolve(value, evaluation)));
                yield resolved;
            }
            case List<?> list -> {
                List<Object> resolved = new ArrayList<>(list.size());
                list.forEach(element -> resolved.add(resolve(element, evaluation)));
                yield resolved;
            }
            case io.tesseraql.core.expr.Expr expr -> expr.eval(evaluation);
            default -> template;
        };
    }
}
