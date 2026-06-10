package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * SQL execution binding for a route or step (design ch. 6.3).
 *
 * @param file    the {@code .sql} file relative to the owning YAML file's directory
 * @param mode    execution mode: {@code query}, {@code query-one}, {@code update}, etc. (ch. 28.6)
 * @param params  mapping of bind name to a source expression such as {@code query.q} or
 *                {@code principal.claim.tenant_id} (design ch. 6.3)
 * @param service a named runtime service provider invoked instead of SQL, exposing non-SQL
 *                runtime state (lanes, traces, file trees, ...) to the route (design ch. 47)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqlBinding(String file, String contract, String mode, Map<String, String> params,
        String service, Materialize materialize) {

    public SqlBinding {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Whether this binding executes a named Identity SQL Contract instead of a SQL file. */
    public boolean isContract() {
        return contract != null && !contract.isBlank();
    }

    /** Whether this binding invokes a named runtime service provider instead of SQL. */
    public boolean isService() {
        return service != null && !service.isBlank();
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
