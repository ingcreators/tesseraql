package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Assignee resolution for a {@link TransitionSpec} (roadmap Phase 28): a 2-way SQL contract that,
 * given a document, returns the principals (or candidate groups) who receive the resulting task —
 * the dual of a data scope over the same org-unit graph.
 *
 * <p>Parsed and linted in slice 1 (its {@code file} must exist), consumed in slice 2 when the task
 * inbox lands.
 *
 * @param file   the assignee-resolution SQL file, relative to the workflow document's directory
 * @param params bind expressions for the contract, resolved against the request context per call
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignSpec(String file, Map<String, String> params) {

    public AssignSpec {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
