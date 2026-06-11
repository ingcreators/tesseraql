package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Declarative error mapping for a route (roadmap Phase 18).
 *
 * <p>{@code constraints} maps database constraint names to field-level errors, so a unique or
 * foreign-key violation surfaces as a usable field error instead of an opaque 500:
 *
 * <pre>{@code
 * errors:
 *   constraints:
 *     uq_users_email:
 *       field: email
 *       code: duplicate
 * }</pre>
 *
 * @param constraints constraint name (as defined in the schema) to its field-level mapping
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorsSpec(Map<String, ConstraintMapping> constraints) {

    public ErrorsSpec {
        constraints = constraints == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(constraints));
    }

    /**
     * A field-level mapping for one database constraint.
     *
     * @param field the input field the violation is reported against
     * @param code  a stable application error code (defaults to the violation kind, e.g.
     *              {@code duplicate} for unique violations)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConstraintMapping(String field, String code) {
    }
}
