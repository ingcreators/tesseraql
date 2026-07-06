package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * SQL execution binding for a route or step (design ch. 6.3, roadmap Phase 18).
 *
 * @param file     the {@code .sql} file relative to the owning YAML file's directory
 * @param mode     execution mode: {@code query}, {@code query-one}, {@code update}, etc. (ch. 28.6)
 * @param params   mapping of bind name to a source expression such as {@code query.q} or
 *                 {@code principal.claim.tenant_id} (design ch. 6.3)
 * @param service  a named runtime service provider invoked instead of SQL, exposing non-SQL
 *                 runtime state (lanes, traces, file trees, ...) to the route (design ch. 47)
 * @param sequence a managed document-number sequence allocated instead of executing SQL; the
 *                 gapless value rides the command transaction's row lock (roadmap Phase 18)
 * @param keys     generated-key columns captured from an insert, published as
 *                 {@code <step>.keys.<column>} for later steps and the response
 * @param expect   declared row-count expectation turning silent lost updates into conflicts
 * @param timeoutSeconds per-binding SQL statement timeout override (roadmap Phase 45); the
 *                 global default is {@code tesseraql.sql.timeoutSeconds}, and {@code 0}
 *                 disables the guard for a deliberately long-running statement
 * @param datasource the named connector under {@code tesseraql.datasources} this read query runs
 *                 on (roadmap Phase 53), overriding the route's connector; legal only on read
 *                 bindings — a step inside a transactional pipeline cannot pick its own connector
 *                 ({@code TQL-YAML-1037}), because the pipeline is one transaction on one
 *                 connection
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqlBinding(String file, String contract, String mode, Map<String, String> params,
        String service, Materialize materialize, String sequence, java.util.List<String> keys,
        Expect expect, Integer timeoutSeconds, String datasource) {

    public SqlBinding {
        params = params == null ? Map.of() : Map.copyOf(params);
        keys = keys == null ? java.util.List.of() : java.util.List.copyOf(keys);
    }

    /** The pre-Phase-45 shape (no per-binding statement timeout), for positional callers. */
    public SqlBinding(String file, String contract, String mode, Map<String, String> params,
            String service, Materialize materialize, String sequence,
            java.util.List<String> keys, Expect expect) {
        this(file, contract, mode, params, service, materialize, sequence, keys, expect, null,
                null);
    }

    /** The pre-Phase-53 shape (no per-binding connector), for positional callers. */
    public SqlBinding(String file, String contract, String mode, Map<String, String> params,
            String service, Materialize materialize, String sequence,
            java.util.List<String> keys, Expect expect, Integer timeoutSeconds) {
        this(file, contract, mode, params, service, materialize, sequence, keys, expect,
                timeoutSeconds, null);
    }

    /** Whether this binding executes a named Identity SQL Contract instead of a SQL file. */
    public boolean isContract() {
        return contract != null && !contract.isBlank();
    }

    /** Whether this binding allocates a managed document-number sequence instead of SQL. */
    public boolean isSequence() {
        return sequence != null && !sequence.isBlank();
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

    /**
     * Declared row-count expectation for a command statement (roadmap Phase 18). A mismatch turns
     * a silent lost update into an explicit error instead of reporting success.
     *
     * @param rows       the exact number of rows the statement must affect
     * @param onMismatch {@code conflict} (default, HTTP 409 with a conflict hint) or
     *                   {@code error} (HTTP 500)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(Integer rows, String onMismatch) {

        /** Returns the effective mismatch behavior, defaulting to {@code conflict}. */
        public String effectiveOnMismatch() {
            return onMismatch == null || onMismatch.isBlank() ? "conflict" : onMismatch;
        }
    }
}
