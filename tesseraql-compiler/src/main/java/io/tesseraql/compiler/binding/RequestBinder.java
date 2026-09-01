package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.InputPolicy;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pipeline step that binds an HTTP request into the TesseraQL execution context (design ch. 7.2,
 * the {@code tesseraqlHttpRequestBinder}).
 *
 * <p>It parses the JSON body, enforces the mass-assignment guard (unknown and non-writable fields,
 * design ch. 33.1/33.2), validates and coerces declared inputs, builds the
 * {@code query}/{@code body}/{@code params} context, publishes the authenticated principal, and
 * resolves the route's {@code sql.params} source expressions into bind values.
 */
public final class RequestBinder implements Step {

    private static final TqlErrorCode FIELD_REJECTED = new TqlErrorCode(TqlDomain.FIELD, 2002);
    private static final System.Logger LOG = System.getLogger(RequestBinder.class.getName());
    /**
     * Framework-reserved request fields that are not application inputs and never bound: the
     * hidden CSRF token a no-JS form post carries (validated by the {@code csrf} step) passes the
     * mass-assignment guard even under {@code unknownFields: reject}.
     */
    private static final java.util.Set<String> RESERVED_FIELDS = java.util.Set.of("_csrf",
            "_idempotency", "_return",
            // The snapshot pager's framework-owned fields (docs/list-surface.md decision 10):
            // the membership tokens and the page number travel in the POST body, like the
            // framework-owned ?page=/?size= query params they mirror.
            "keys", "page", "size");

    private final RouteDefinition route;
    private final java.util.List<String> pathParams;
    private final java.nio.file.Path appHome;
    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();
    /** Pre-compiled {@code requiredWhen} conditions (roadmap Phase 40) — bad syntax fails the build. */
    private final Map<String, io.tesseraql.core.expr.Expr> requiredWhen = new LinkedHashMap<>();
    /**
     * The code-input names the route's {@code lookup:} fields declare
     * (docs/reference-lookup.md decision 2). The visible code rides the form post beside the
     * hidden id, but it is presentation: accepted past the mass-assignment guard because the
     * same declaration names it, then dropped — only the id binds.
     */
    private final java.util.Set<String> lookupCodeFields = new java.util.LinkedHashSet<>();
    /** What binding an object array's elements needs: this route's input policy and functions. */
    private final InputBinder.ElementRules elements;

    public RequestBinder(RouteDefinition route) {
        this(route, null, null,
                io.tesseraql.core.expr.ExpressionFunctions.processDefault());
    }

    public RequestBinder(RouteDefinition route, String urlPath) {
        this(route, urlPath, null,
                io.tesseraql.core.expr.ExpressionFunctions.processDefault());
    }

    /**
     * @param urlPath the route's URL template ({@code /users/{id}/roles}), or null for a
     *                document with no URL of its own
     */
    public RequestBinder(RouteDefinition route, String urlPath,
            java.nio.file.Path appHome, io.tesseraql.core.expr.ExpressionFunctions functions) {
        this.route = route;
        this.pathParams = declaredPathParams(urlPath);

        this.appHome = appHome;
        this.elements = new InputBinder.ElementRules(route.effectiveInputPolicy(), functions);
        route.input().forEach((name, field) -> {
            if (field.lookup() != null && field.lookup().code() != null
                    && !route.input().containsKey(field.lookup().code())) {
                lookupCodeFields.add(field.lookup().code());
            }
            if (field.requiredWhen() != null && !field.requiredWhen().isBlank()) {
                requiredWhen.put(name,
                        io.tesseraql.core.expr.ExpressionParser.parse(field.requiredWhen(),
                                functions));
            }
            // An element's requiredWhen is evaluated per element against its own item.* scope,
            // so it is not held here — but it is parsed here, so bad syntax fails the build
            // like every other declared condition rather than the first request that hits it.
            if (field.items() != null) {
                field.items().fields().values().forEach(element -> {
                    if (element.requiredWhen() != null && !element.requiredWhen().isBlank()) {
                        io.tesseraql.core.expr.ExpressionParser.parse(element.requiredWhen(),
                                functions);
                    }
                });
            }
        });
    }

    @Override
    public void process(Exchange exchange) {
        io.tesseraql.core.telemetry.Span span = io.tesseraql.pipeline.TesseraqlTracing
                .tracer(exchange)
                .start("tesseraql.request.bind",
                        io.tesseraql.pipeline.TesseraqlTracing.parent(exchange))
                .attribute("routeId", route.id());
        try {
            bind(exchange);
        } catch (RuntimeException ex) {
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    private void bind(Exchange exchange) {
        Map<String, Object> body = parseBody(exchange);
        guardMassAssignment(exchange, body);
        Map<String, String> fromPath = pathValues(exchange);

        // The negotiated request locale (roadmap Phase 22) drives date/number input parsing.
        String localeTag = exchange.getProperty(TesseraqlProperties.LOCALE, "en", String.class);
        Map<String, Object> effective = InputBinder.bind(route.input(),
                name -> rawValue(name, body, exchange, fromPath),
                name -> body.get(name),
                java.util.Locale.forLanguageTag(localeTag),
                exchange.beans().lookup(
                        TesseraqlProperties.CATALOG_STORE_BEAN,
                        io.tesseraql.core.catalog.CatalogStore.class),
                elements);

        // A path parameter declared under input: publishes its coerced, validated value in the
        // path.* namespace too (roadmap Phase 40 typed path params); undeclared ones stay raw.
        // Either way the value came from the URL — the declaration types and validates a path
        // parameter, it never sources one.
        Map<String, Object> path = new LinkedHashMap<>();
        for (String name : pathParams) {
            path.put(name, effective.containsKey(name) ? effective.get(name) : fromPath.get(name));
        }

        Map<String, Object> context = new HashMap<>();
        context.put("query", effective);
        context.put("params", effective);
        context.put("body", body);
        context.put("path", path);
        context.put("principal", exchange.getProperty(TesseraqlProperties.PRINCIPAL));
        context.put("tenant", exchange.getProperty(TesseraqlProperties.TENANT));
        // The app's live feature flags (config/flags.yml), resolvable as flags.<name> in expressions,
        // templates, and validation. Read live (stat-cheap re-read) so a Studio flag edit takes effect
        // on the next request; absent file ⇒ an empty map.
        if (appHome != null) {
            context.put("flags", io.tesseraql.yaml.flags.FlagsSpec.live(appHome).values());
        }
        // The negotiated request locale (roadmap Phase 22), resolvable as request.locale.
        String locale = exchange.getProperty(TesseraqlProperties.LOCALE, String.class);
        if (locale != null) {
            context.put("request", Map.of("locale", locale));
        }
        // Declared app preferences (config/preferences.yml, roadmap Phase 48), resolvable as
        // preference.<key>: the signed-in user's stored app.<key> when present, else the
        // declared default. Only DECLARED keys appear - the namespace is bounded by the
        // declaration, never by what happens to be in the store.
        if (appHome != null) {
            io.tesseraql.yaml.account.PreferencesSpec declared = io.tesseraql.yaml.account.PreferencesSpec
                    .live(appHome);
            if (!declared.isEmpty()) {
                Map<String, String> stored = Map.of();
                io.tesseraql.core.account.PreferenceStore preferences = exchange.beans().lookup(
                        TesseraqlProperties.PREFERENCE_STORE_BEAN,
                        io.tesseraql.core.account.PreferenceStore.class);
                if (preferences != null && exchange.getProperty(
                        TesseraqlProperties.PRINCIPAL) instanceof io.tesseraql.security.Principal principal) {
                    stored = preferences.preferences(principal.tenantId(),
                            principal.subject());
                }
                Map<String, Object> values = new java.util.LinkedHashMap<>();
                for (io.tesseraql.yaml.account.PreferencesSpec.Field field : declared.fields()) {
                    String value = stored.get("app." + field.key());
                    if (value == null) {
                        value = field.defaultValue();
                    }
                    if (value != null) {
                        values.put(field.key(), value);
                    }
                }
                context.put("preference", values);
            }
        }

        // Conditional requiredness (requiredWhen): with every input coerced and the request
        // context assembled, an absent field whose condition holds is rejected like required.
        if (!requiredWhen.isEmpty()) {
            EvaluationContext evaluation = new EvaluationContext(context);
            for (Map.Entry<String, io.tesseraql.core.expr.Expr> entry : requiredWhen.entrySet()) {
                if (!effective.containsKey(entry.getKey())
                        && entry.getValue().evalBoolean(evaluation)) {
                    throw InputBinder.missingRequired(entry.getKey());
                }
            }
        }

        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        exchange.setProperty(TesseraqlProperties.SQL_PARAMS, resolveSqlParams(context));

    }

    /** Whether {@code name} is a declared {@code type: array} input of this route. */
    private boolean isArrayInput(String name) {
        return route != null && route.input() != null && route.input().get(name) != null
                && "array".equals(route.input().get(name).type());
    }

    private Map<String, Object> parseBody(Exchange exchange) {
        // A form has one representation (docs/vertx-native.md decision 2): the edge parsed it
        // into request().formFields(), and the third path this used to carry — parsing a raw
        // urlencoded body itself — parsed what the edge had already parsed.
        if (!exchange.request().formFields().isEmpty()) {
            Map<String, Object> form = new LinkedHashMap<>();
            exchange.request().formFields().forEach((name, values) -> form.put(name,
                    // A declared array input keeps its list-ness even with one value — a
                    // checkbox group with one box checked is still a selection of one
                    // (docs/list-surface.md decision 9), not a scalar.
                    values.size() == 1 && !isArrayInput(name)
                            ? values.get(0)
                            : new java.util.ArrayList<>(values)));
            return form;
        }
        // A programmatic caller (an MCP primitive, a delegated workflow step) hands the bound
        // values as a Map body; use it directly.
        if (exchange.getBody() instanceof Map<?, ?> formBody) {
            Map<String, Object> form = new LinkedHashMap<>();
            formBody.forEach((key, value) -> form.put(String.valueOf(key), value));
            return form;
        }
        String raw = exchange.getBody(String.class);
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

    private void guardMassAssignment(Exchange exchange, Map<String, Object> body) {
        if (body.isEmpty()) {
            return;
        }
        InputPolicy policy = route.effectiveInputPolicy();
        java.util.List<String> dropped = new java.util.ArrayList<>();
        for (String key : body.keySet()) {
            if (RESERVED_FIELDS.contains(key)) {
                continue;
            }
            InputField field = route.input().get(key);
            if (field == null) {
                // A lookup field's visible code input (docs/reference-lookup.md): declared
                // by the same lookup: block, presentation-only — treated as not supplied.
                if (lookupCodeFields.contains(key)) {
                    dropped.add(key);
                    continue;
                }
                if (policy.rejectsUnknownFields()) {
                    throw new TqlException(FIELD_REJECTED, "Unknown input field '" + key + "'");
                }
                continue;
            }
            // A field the principal's policy does not permit is exactly a non-writable field
            // for this request (docs/view-composition.md wave 4): server truth first — the
            // rendered form omitting it is derived from this same declaration.
            boolean deniedByPolicy = field.policy() != null
                    && !permitsWrite(exchange, field.policy());
            if (!field.isWritable() || deniedByPolicy) {
                switch (policy.readOnlyBehaviorOrDefault()) {
                    case "ignore" -> dropped.add(key);
                    case "warn" -> {
                        LOG.log(System.Logger.Level.WARNING,
                                "Ignoring non-writable input field ''{0}''", key);
                        dropped.add(key);
                    }
                    default -> throw new TqlException(FIELD_REJECTED, deniedByPolicy
                            ? "Field '" + key + "' is not writable for this principal"
                            : "Field '" + key + "' is not writable");
                }
            }
        }
        // ignore/warn mean "treat as not supplied": the value must not reach the binder — a
        // dropped-but-bound value would defeat the guard.
        dropped.forEach(body::remove);
    }

    /** Whether the principal satisfies a field's write {@code policy:}. Fails safe. */
    private boolean permitsWrite(Exchange exchange, String policyId) {
        io.tesseraql.security.policy.PolicyEngine engine = exchange.beans().lookup(
                TesseraqlProperties.POLICY_ENGINE_BEAN,
                io.tesseraql.security.policy.PolicyEngine.class);
        io.tesseraql.security.Principal principal = exchange.getProperty(
                TesseraqlProperties.PRINCIPAL, io.tesseraql.security.Principal.class);
        return engine != null && engine.permits(policyId, principal);
    }

    private String rawValue(String name, Map<String, Object> body, Exchange exchange,
            Map<String, String> fromPath) {
        // A declared input that is also a path parameter is typed by its declaration and
        // sourced by the URL. Reading the body first here let a field of that name replace the
        // segment the request was addressed to — in path.*, in params.*, and in every bind
        // downstream — so a route saying path.id could be handed an id the body chose.
        if (fromPath.containsKey(name)) {
            return fromPath.get(name);
        }
        if (body.containsKey(name) && body.get(name) != null) {
            return String.valueOf(body.get(name));
        }
        java.util.List<String> query = exchange.request().queryParams().get(name);
        if (query != null && !query.isEmpty()) {
            return query.get(0);
        }
        return exchange.request().header(name);
    }

    /**
     * The request's path parameters: what the router matched, under the declared names.
     *
     * <p>{@code path.id} means the URL, and this is the URL — the edge maps the router's
     * wire-safe stand-ins back before any step runs (docs/vertx-native.md decision 2), so the
     * re-parse that used to recover these from the URI string has nothing left to recover. A
     * document reached without a URL of its own (an MCP primitive, a delegated workflow step)
     * declares no path parameters, so this is empty and nothing reads it.
     */
    private Map<String, String> pathValues(Exchange exchange) {
        if (pathParams.isEmpty()) {
            return Map.of();
        }
        return exchange.request().pathParams();
    }

    /** The {@code {name}} parameters a URL template declares, in template order. */
    private static java.util.List<String> declaredPathParams(String urlPath) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (urlPath != null) {
            java.util.regex.Matcher matcher = io.tesseraql.core.sql.SqlIdentifiers.PLACEHOLDER
                    .matcher(urlPath);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return java.util.List.copyOf(names);
    }

    /** Every binding whose {@code params:} this route's SQL execution can read. */
    private java.util.List<Binding> bindings() {
        java.util.List<Binding> bindings = new java.util.ArrayList<>();
        if (route.main() != null) {
            bindings.add(route.main());
        }
        return bindings;
    }

    private Map<String, Object> resolveSqlParams(Map<String, Object> context) {
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> sqlParams = new LinkedHashMap<>();
        // The route's own binding, wherever the recipe keeps it. A file-export declares its
        // query at export.sql, and reading route.main() alone meant every params: entry there
        // resolved to a silent null bind — while the *same* keys under a route-level sql: did
        // reach the export query, because nothing rejected them. Two spellings, one working.
        for (Binding binding : bindings()) {
            binding.params().forEach((bindName, sourceExpr) -> sqlParams.put(bindName,
                    evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        }
        // Ambient principal.* binds (docs/ambient-params.md); declared params win by name.
        io.tesseraql.core.sql.AmbientBinds.seed(sqlParams, evaluation);
        return sqlParams;
    }
}
