package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.tesseraql.core.util.OrderedCopies;
import java.util.Map;

/**
 * Assignee resolution for a {@link TransitionSpec} (roadmap Phase 28): a 2-way SQL contract that,
 * given a document, returns the principals (or candidate groups) who receive the resulting task —
 * the dual of a data scope over the same org-unit graph.
 *
 * <p>The contract is told its document: the resolver binds {@code /* key *}{@code /} (the
 * resolved document key) exactly as the transition's guard and command do, plus the ambient
 * {@code principal.*}, {@code decision.*} and {@code audit.*} binds every statement in the
 * transition's transaction sees. {@code params:} adds to that set and wins on a clash. It was the
 * one document-keyed contract in the engine that saw no key: a {@code /* key *}{@code /} bound
 * null, no task opened, and the task-authority gate silently never engaged
 * (docs/audit-low-leads.md G32).
 *
 * <p>Parsed and linted in slice 1 (its {@code file} must exist, and each {@code params:} key must
 * be a bind name — TQL-SQL-2120), consumed in slice 2 when the task inbox lands.
 *
 * @param file   the assignee-resolution SQL file, relative to the workflow document's directory
 * @param params bind expressions for the contract, resolved against the request context per call
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignSpec(String file, Map<String, String> params) {

    public AssignSpec {
        params = params == null ? Map.of() : OrderedCopies.map(params);
    }
}
