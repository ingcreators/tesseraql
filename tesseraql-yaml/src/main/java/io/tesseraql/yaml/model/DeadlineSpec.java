package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A per-state deadline of a {@link WorkflowDefinition} (roadmap Phase 28): when a document sits in
 * {@code state} longer than {@code within}, the cluster-safe sweeper applies {@code onBreach}.
 *
 * <p>Parsed and linted in slice 1 so the YAML shape is fixed early; the sweeper that consumes it
 * lands in slice 3.
 *
 * @param state    the state the deadline applies to
 * @param within   the duration before breach, e.g. {@code 48h}
 * @param onBreach the escalation or reassignment to apply on breach
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeadlineSpec(String state, String within, OnBreachSpec onBreach) {

    /** What the sweeper does when a deadline is breached (roadmap Phase 28, consumed in slice 3). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OnBreachSpec(String escalate, String reassign) {
    }
}
