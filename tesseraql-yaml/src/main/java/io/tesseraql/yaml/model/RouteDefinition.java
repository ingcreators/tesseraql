package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * @param errors  declarative error mapping, e.g. constraint names to field-level errors
 * @param fileImport the {@code import:} block of a {@code file-import} route
 * @param fileExport the {@code export:} block of a {@code file-export} route
 * @param response response shape
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
        ErrorsSpec errors,
        @com.fasterxml.jackson.annotation.JsonProperty("import") ImportSpec fileImport,
        @com.fasterxml.jackson.annotation.JsonProperty("export") ExportSpec fileExport,
        ResponseSpec response) {

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
    }

    /** The input policy, or framework defaults (reject unknown / reject read-only). */
    public InputPolicy effectiveInputPolicy() {
        return inputPolicy == null ? InputPolicy.defaults() : inputPolicy;
    }
}
