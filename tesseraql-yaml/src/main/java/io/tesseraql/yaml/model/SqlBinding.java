package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * SQL execution binding for a route or step (design ch. 6.3).
 *
 * @param file   the {@code .sql} file relative to the owning YAML file's directory
 * @param mode   execution mode: {@code query}, {@code query-one}, {@code update}, etc. (ch. 28.6)
 * @param params mapping of bind name to a source expression such as {@code query.q} or
 *               {@code principal.claim.tenant_id} (design ch. 6.3)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqlBinding(String file, String mode, Map<String, String> params, Materialize materialize) {

    public SqlBinding {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Returns the effective mode, defaulting to {@code query}. */
    public String effectiveMode() {
        return mode == null || mode.isBlank() ? "query" : mode;
    }

    /**
     * Per-route result materialization guard (design ch. 28.7).
     *
     * @param maxRows    maximum rows that may be materialized in memory
     * @param onOverflow behavior when exceeded: {@code fail} (default) or {@code warn}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Materialize(Integer maxRows, String onOverflow) {
    }
}
