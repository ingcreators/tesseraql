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
 * @param steps   ordered SQL steps of a {@code command-json} route, executed in one transaction;
 *                later steps can bind values produced by earlier ones (roadmap Phase 18)
 * @param sources every named read acquisition, in authored order, each bound into the
 *                execution context under its own name so one page can compose several results;
 *                an entry names its own mechanism (docs/unified-sources.md)
 * @param validate declarative validation rules of a command, keyed by rule id and evaluated in
 *                their authored order before the command's steps (roadmap Phase 19)
 * @param decide  decision-table references of a command (docs/decision-tables.md), keyed by
 *                alias and evaluated once, in authored order, before the {@code validate:}
 *                rules; outputs publish into the context as {@code decision.<alias>.<output>}
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
        AdmissionSpec admission,
        OutboxSpec outbox,
        Map<String, Binding> steps,
        // Every named read acquisition: one map, whatever mechanism each entry names
        // (docs/unified-sources.md). Replaced queries: plus a parallel http: map, which made
        // the map the discriminator instead of the entry's own arm.
        Map<String, Binding> sources,
        Map<String, ValidationRule> validate,
        // Named decision-table references evaluated once per operation before validate: rules,
        // published under decision.* (docs/decision-tables.md).
        Map<String, DecisionUse> decide,
        // "notify" itself is not a legal record component (it would hide Object.notify()).
        @com.fasterxml.jackson.annotation.JsonProperty("notify") Map<String, NotifySpec> notifications,
        ErrorsSpec errors,
        @com.fasterxml.jackson.annotation.JsonProperty("import") ImportSpec fileImport,
        @com.fasterxml.jackson.annotation.JsonProperty("export") ExportSpec fileExport,
        WebhookSpec webhook,
        PublishSpec publish,
        ConsumeSpec consume,
        ResponseSpec response,
        PageSpec pagination,
        String datasource,
        // Reference lookups folded into a result set's rows, keyed by the rows themselves
        // (docs/lookups.md).
        Map<String, EnrichSpec> enrich,
        // Declarative HTTP caching for query responses (docs/response-shaping.md).
        CacheSpec cache,
        // Topics broadcast to live views after a successful command commit (docs/realtime.md);
        // a single string or a list in YAML.
        @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> emit,
        // Source tables whose code catalogs this command's write makes stale
        // (docs/lookups.md, decision 13); a single string or a list in YAML.
        @com.fasterxml.jackson.annotation.JsonFormat(with = com.fasterxml.jackson.annotation.JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> invalidates) {

    public RouteDefinition {
        input = input == null ? Map.of() : Map.copyOf(input);
        // Insertion-ordered so command steps and named sources run in their authored order.
        steps = steps == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(steps));
        sources = sources == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sources));
        validate = validate == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(validate));
        decide = decide == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(decide));
        notifications = notifications == null
                ? Map.of()
                : java.util.Collections
                        .unmodifiableMap(new java.util.LinkedHashMap<>(notifications));
        emit = emit == null ? List.of() : List.copyOf(emit);
        invalidates = invalidates == null ? List.of() : List.copyOf(invalidates);
        enrich = enrich == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(enrich));
    }

    /**
     * A route the framework synthesizes rather than an author writing it: a workflow
     * transition, a dispatch selector, a delegate. Named because the alternative is a
     * positional call listing two dozen nulls, which says nothing about which of them matter
     * and breaks on every model change — this one names the five that do.
     */
    public static RouteDefinition synthesizedCommand(String id, SecuritySpec security,
            Binding command, Map<String, DecisionUse> decide, ResponseSpec response) {
        return new RouteDefinition("tesseraql/v1", id, "route", "command-json", Map.of(), null,
                security, null, null, null,
                command == null ? Map.of() : Map.of("command", command),
                Map.of(), Map.of(), decide, Map.of(),
                null, null, null, null, null, null, response, null, null, null, null, null,
                null);
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
                idempotency, admission, outbox, steps, sources, validate, decide,
                notifications,
                errors, fileImport, fileExport, webhook, publish, consume, response, pagination,
                datasource, enrich, cache, emit, invalidates);
    }

    /**
     * A copy carrying resolved {@code input:} fields and error mapping — how the manifest loader
     * stamps field-domain references and the app constraint catalog (docs/field-domains.md) into
     * the route, so every downstream consumer sees effective values.
     */
    public RouteDefinition withInputAndErrors(Map<String, InputField> effectiveInput,
            ErrorsSpec effectiveErrors) {
        if (effectiveInput == input && effectiveErrors == errors) {
            return this;
        }
        return new RouteDefinition(version, id, kind, recipe, effectiveInput, inputPolicy,
                security, idempotency, admission, outbox, steps, sources, validate, decide,
                notifications, effectiveErrors, fileImport, fileExport, webhook, publish, consume,
                response, pagination, datasource, enrich, cache, emit, invalidates);
    }

    /**
     * A copy carrying resolved {@code validate:} rules — how the manifest loader stamps shared
     * rule-set references (docs/validation-rule-sets.md) into the route.
     */
    public RouteDefinition withValidate(Map<String, ValidationRule> effective) {
        if (effective == validate) {
            return this;
        }
        return new RouteDefinition(version, id, kind, recipe, input, inputPolicy, security,
                idempotency, admission, outbox, steps, sources, effective, decide,
                notifications, errors, fileImport, fileExport, webhook, publish, consume,
                response, pagination, datasource, enrich, cache, emit, invalidates);
    }

    /**
     * A copy carrying resolved {@code decide:} references — how the manifest loader stamps
     * shared decision-table references (docs/decision-tables.md) into the route, so the
     * compiler builds the runtime tables from the route alone.
     */
    public RouteDefinition withDecide(Map<String, DecisionUse> effective) {
        if (effective == decide) {
            return this;
        }
        return new RouteDefinition(version, id, kind, recipe, input, inputPolicy, security,
                idempotency, admission, outbox, steps, sources, validate, effective,
                notifications, errors, fileImport, fileExport, webhook, publish, consume,
                response, pagination, datasource, enrich, cache, emit, invalidates);
    }

    /** The input policy, or framework defaults (reject unknown / reject read-only). */
    public InputPolicy effectiveInputPolicy() {
        return inputPolicy == null ? InputPolicy.defaults() : inputPolicy;
    }

    /** The reserved source name every default resolves to (docs/unified-sources.md). */
    public static final String MAIN = "main";

    /**
     * The primary source, or {@code null} when the document declares none.
     *
     * <p>A naming convention, not a slot: {@code main} is the source an omitted
     * {@code response.json.body}, a list view's omitted {@code source:}, a {@code pagination:}
     * target and an export's extraction resolve to. A document that uses none of those
     * defaults needs no {@code main} — a form page, a command, a dashboard naming three equal
     * sources — and the features that do require one say so themselves, each with a lint that
     * names the feature rather than the slot.
     */
    public Binding main() {
        return sources.get(MAIN);
    }

    /** The connector the route's SQL runs on: the declared {@code datasource:}, else {@code main}. */
    public String effectiveDatasource() {
        return datasource == null || datasource.isBlank() ? "main" : datasource;
    }
}
