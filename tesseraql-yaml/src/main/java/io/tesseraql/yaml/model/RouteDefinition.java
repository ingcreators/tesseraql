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
 * @param queries additional named queries executed after {@code sql}, each bound into the
 *                execution context under its own name so one page can render several result sets
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
        Map<String, SqlBinding> queries,
        @com.fasterxml.jackson.annotation.JsonProperty("import") ImportSpec fileImport,
        @com.fasterxml.jackson.annotation.JsonProperty("export") ExportSpec fileExport,
        ResponseSpec response) {

    public RouteDefinition {
        input = input == null ? Map.of() : Map.copyOf(input);
        // Insertion-ordered so named queries execute in their authored order.
        queries = queries == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(queries));
    }

    /** The input policy, or framework defaults (reject unknown / reject read-only). */
    public InputPolicy effectiveInputPolicy() {
        return inputPolicy == null ? InputPolicy.defaults() : inputPolicy;
    }
}
