package io.tesseraql.yaml.model;

import java.util.Map;

/**
 * One parsed {@code domains/*.yml} document (docs/field-domains.md): named field domains plus
 * app-level constraint-catalog entries. Aggregation into the app-wide namespace — duplicate
 * detection across files — is {@link io.tesseraql.yaml.domain.FieldDomains}' concern.
 *
 * @param domains     declared field domains by name
 * @param constraints database constraint names mapped once for every route
 */
public record DomainsDocument(Map<String, InputField> domains,
        Map<String, ErrorsSpec.ConstraintMapping> constraints) {

    public DomainsDocument {
        domains = domains == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(domains));
        constraints = constraints == null
                ? Map.of()
                : java.util.Collections
                        .unmodifiableMap(new java.util.LinkedHashMap<>(constraints));
    }
}
