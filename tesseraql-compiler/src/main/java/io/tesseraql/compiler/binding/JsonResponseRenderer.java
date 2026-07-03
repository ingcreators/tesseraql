package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.PolicyEngine;
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
    private final Object compiledBody;
    private final java.util.List<CompiledStatus> statusWhen;
    private final ObjectMapper mapper = new ObjectMapper();

    /** A pre-compiled statusWhen arm (roadmap Phase 41): first truthy condition wins. */
    record CompiledStatus(io.tesseraql.core.expr.Expr when, int status) {
    }

    public JsonResponseRenderer(ResponseSpec.JsonResponse response) {
        this.response = response;
        this.compiledBody = compile(response.body());
        this.statusWhen = response.statusWhen().stream()
                .map(arm -> new CompiledStatus(
                        io.tesseraql.core.expr.ExpressionParser.parse(arm.when()), arm.status()))
                .toList();
    }

    /**
     * Pre-compiles the body template's leaf strings as core-language expressions (roadmap
     * Phase 41): a plain dotted path parses identically to the legacy resolver, and computed
     * leaves ({@code params.qty * params.price}, {@code upper(...)}) come along for free. A
     * leaf the parser rejects falls back to legacy dotted-path resolution, so pre-Phase-41
     * bodies keep their exact behavior.
     */
    private static Object compile(Object template) {
        return switch (template) {
            case null -> null;
            case Map<?, ?> map -> {
                Map<String, Object> compiled = new LinkedHashMap<>();
                map.forEach((key, value) -> compiled.put(String.valueOf(key), compile(value)));
                yield compiled;
            }
            case List<?> list -> {
                List<Object> compiled = new ArrayList<>(list.size());
                list.forEach(element -> compiled.add(compile(element)));
                yield compiled;
            }
            case String expression -> {
                try {
                    yield io.tesseraql.core.expr.ExpressionParser.parse(expression);
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
        body = nest(body, context);
        if (!response.fields().isEmpty()) {
            PolicyEngine policyEngine = exchange.getContext().getRegistry()
                    .lookupByNameAndType(TesseraqlProperties.POLICY_ENGINE_BEAN,
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

        int status = response.effectiveStatus();
        for (CompiledStatus arm : statusWhen) {
            if (arm.when().evalBoolean(evaluation)) {
                status = arm.status();
                break;
            }
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(json);
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

    /**
     * Nested composition (roadmap Phase 41): attaches each nest:'s child rows under the
     * matching parent rows of a body key, grouped by the declared join key. Parents are
     * copied, so shared context rows are never mutated.
     */
    @SuppressWarnings("unchecked")
    private Object nest(Object body, Map<String, Object> context) {
        if (response.nest().isEmpty() || !(body instanceof Map)) {
            return body;
        }
        Map<String, Object> shaped = (Map<String, Object>) body;
        for (ResponseSpec.NestSpec nest : response.nest()) {
            Object parentsRaw = shaped.get(nest.into());
            Object childResult = context.get(nest.children());
            if (!(parentsRaw instanceof List) || !(childResult instanceof Map)) {
                continue;
            }
            Object childRowsRaw = ((Map<String, Object>) childResult).get("rows");
            if (!(childRowsRaw instanceof List)) {
                continue;
            }
            var entry = nest.on().entrySet().iterator().next();
            String parentKey = entry.getKey();
            String childKey = entry.getValue();
            Map<Object, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (Object childRaw : (List<Object>) childRowsRaw) {
                if (childRaw instanceof Map) {
                    Map<String, Object> child = (Map<String, Object>) childRaw;
                    grouped.computeIfAbsent(normalize(child.get(childKey)),
                            key -> new ArrayList<>()).add(child);
                }
            }
            List<Object> parents = new ArrayList<>();
            for (Object parentRaw : (List<Object>) parentsRaw) {
                if (parentRaw instanceof Map) {
                    Map<String, Object> parent = new LinkedHashMap<>(
                            (Map<String, Object>) parentRaw);
                    parent.put(nest.as(), grouped.getOrDefault(
                            normalize(parent.get(parentKey)), List.of()));
                    parents.add(parent);
                } else {
                    parents.add(parentRaw);
                }
            }
            shaped.put(nest.into(), parents);
        }
        return shaped;
    }

    /** Join keys compare by canonical text, so INTEGER 1 matches BIGINT 1 across drivers. */
    private static Object normalize(Object key) {
        return key == null ? null : String.valueOf(key);
    }
}
