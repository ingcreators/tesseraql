package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One state of a {@link WorkflowDefinition} (roadmap Phase 28). Exactly one state is {@code initial};
 * {@code terminal} states have no outgoing transitions.
 *
 * @param id   the state id
 * @param type {@code initial}, {@code terminal}, or {@code null} for an ordinary state
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StateSpec(String id, String type) {

    /** Whether this is the workflow's initial state. */
    public boolean isInitial() {
        return "initial".equalsIgnoreCase(type);
    }

    /** Whether this is a terminal state (no outgoing transitions). */
    public boolean isTerminal() {
        return "terminal".equalsIgnoreCase(type);
    }
}
