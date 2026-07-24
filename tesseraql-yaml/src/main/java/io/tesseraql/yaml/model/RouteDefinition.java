package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * A TesseraQL Simple YAML route definition (design ch. 6.1, 6.3).
 *
 * <p>This is the user-authored, source-of-truth model. The compiler transforms it into a Camel
 * route; it is never hand-edited as generated Camel YAML (design ch. 3.1 "source of truth").
 *
 * @param version the DSL version, e.g. {@code tesseraql/v1}
 * @param id      unique route id, e.g. {@code users.search}
 * @param kind    {@code route} or {@code job}
 * @param recipe  the recipe driving compilation, e.g. {@code query-json} (design ch. 6.2)
 * @param input   declared, whitelisted inputs keyed by name
 * @param security authentication and authorization declaration
 * @param sql     SQL execution binding
 * @param steps   ordered SQL steps of a {@code command-json} route, executed in one transaction;
 *                later steps can bind values produced by earlier ones (roadmap Phase 18)
 * @param queries additional named queries executed after {@code sql}, each bound into the
 *                execution context under its own name so one page can render several result sets
 * @param validate declarative validation rules of a command, keyed by rule id and evaluated in
 *                their authored order before the command's steps (roadmap Phase 19)
 * @param notifications the {@code notify:} block of a command, keyed by notification id and
 *                enqueued on the transactional outbox after the steps (roadmap Phase 20)
 * @param errors  declarative error mapping, e.g. constraint names to field-level errors
 * @param fileImport the {@code import:} block of a {@code file-import} route
 * @param fileExport the {@code export:} block of a {@code file-export} route
 * @param webhook the {@code webhook:} block of a {@code webhook} route (roadmap Phase 26)
 * @param publish the {@code publish:} block of a command route, emitting a domain event to a
 *                messaging channel through the transactional outbox (roadmap Phase 27)
 * @param consume the {@code consume:} block of a {@code queue-consume} route, subscribing to a
 *                messaging channel and running the SQL pipeline per message (roadmap Phase 27)
 * @param response response shape
 * @param datasource the named connector under {@code tesseraql.datasources} the route's SQL runs
 *                on (roadmap Phase 53), defaulting to {@code main}; on a read route every binding
 *                runs there (a named read query may override per binding), and a transactional
 *                route moves its whole single-connection transaction there
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteDefinition(
        String version,
        String id,
        String kind,
        String recipe,
        Map<String, InputField> input,
        InputPolicy inputPolicy,
        SecuritySpec security,
        IdempotencySpec idempotency,
        PolicySpec policy,
        OutboxSpec outbox,
        SqlBinding sql,
        Map<String, SqlBinding> steps,
        Map<String, SqlBinding> queries,
        Map<String, ValidationRule> validate,
        // "notify" itself is not a legal record component (it would hide Object.notify()).
        @com.fasterxml.jackson.annotation.JsonProperty("notify") Map<String, NotifySpec> notifications,
        ErrorsSpec errors,
        @com.fasterxml.jackson.annotation.JsonProperty("import") ImportSpec fileImport,
        @com.fasterxml.jackson.annotation.JsonProperty("export") ExportSpec fileExport,
        WebhookSpec webhook,
        PublishSpec publish,
        ConsumeSpec consume,
        ResponseSpec response,
        PageSpec page,
        String datasource,
        // Named http: sources composed with SQL results on query routes (docs/connectors.md).
        Map<String, HttpSourceSpec> http,
        // Declarative HTTP caching for query responses (docs/response-shaping.md).
        CacheSpec cache,
        // Topics broadcast to live views after a successful command commit (docs/realtime.md);
        // a single string or a list in YAML.
        @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> emit) {

    public RouteDefinition {
        input = input == null ? Map.of() : Map.copyOf(input);
        // Insertion-ordered so command steps and named queries execute in their authored order.
        steps = steps == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(steps));
        queries = queries == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(queries));
        validate = validate == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(validate));
        notifications = notifications == null
                ? Map.of()
                : java.util.Collections
                        .unmodifiableMap(new java.util.LinkedHashMap<>(notifications));
        emit = emit == null ? List.of() : List.copyOf(emit);
        http = http == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(http));
    }

    /**
     * A copy of this definition carrying a resolved {@code security:} block — how the manifest
     * loader stamps app-level security defaults (docs/route-defaults.md) into the route, so every
     * downstream consumer sees effective values.
     */
    public RouteDefinition withSecurity(SecuritySpec effective) {
        if (effective == security) {
            return this;
        }
        return new RouteDefinition(version, id, kind, recipe, input, inputPolicy, effective,
                idempotency, policy, outbox, sql, steps, queries, validate, notifications, errors,
                fileImport, fileExport, webhook, publish, consume, response, page, datasource,
                http, cache, emit);
    }

    /** The input policy, or framework defaults (reject unknown / reject read-only). */
    public InputPolicy effectiveInputPolicy() {
        return inputPolicy == null ? InputPolicy.defaults() : inputPolicy;
    }

    /** The connector the route's SQL runs on: the declared {@code datasource:}, else {@code main}. */
    public String effectiveDatasource() {
        return datasource == null || datasource.isBlank() ? "main" : datasource;
    }
}
