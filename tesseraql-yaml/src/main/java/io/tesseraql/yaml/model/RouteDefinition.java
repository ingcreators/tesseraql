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
 * @param response response shape
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteDefinition(
        String version,
        String id,
        String kind,
        String recipe,
        Map<String, InputField> input,
        SecuritySpec security,
        SqlBinding sql,
        ResponseSpec response) {

    public RouteDefinition {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
