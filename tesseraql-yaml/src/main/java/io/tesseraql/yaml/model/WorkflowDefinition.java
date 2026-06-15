package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A {@code kind: workflow} document under {@code workflow/} (roadmap Phase 28 — approval workflow):
 * a SQL-contract state machine driving a business document through declared states by transitions.
 *
 * <p>The compiler synthesizes one transactional-command route per {@link TransitionSpec}; each
 * transition reuses the Phase 18 command engine to advance state, run the author's command, and
 * append an immutable history row in a single transaction. State lives in the managed
 * {@code tql_workflow_instance} table ({@code mode: managed}) or in the business table's
 * {@code stateColumn} ({@code mode: app}), mirroring IAM's managed/SQL realm duality.
 *
 * @param version     the DSL version, e.g. {@code tesseraql/v1}
 * @param id          the workflow id
 * @param kind        always {@code workflow}
 * @param mode        {@code managed} or {@code app}; {@code null} defers to
 *                    {@code tesseraql.workflow.mode}
 * @param document    the business document the workflow governs
 * @param http        the HTTP mounting of the synthesized transition routes
 * @param security    the default security for every transition (a transition may override it)
 * @param initial     the id of the initial state
 * @param states      the declared states
 * @param transitions the declared transitions
 * @param deadlines   per-state deadlines (parsed and linted here; consumed in slice 3)
 * @param reminders   reminder notifications fired on task assignment / escalation (the YAML key is
 *                    {@code notify}, Phase 20 channels), or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(String version, String id, String kind, String mode,
        DocumentSpec document, HttpSpec http, SecuritySpec security, String initial,
        List<StateSpec> states, List<TransitionSpec> transitions, List<DeadlineSpec> deadlines,
        // "notify" itself is not a legal record component (it would hide Object.notify()).
        @com.fasterxml.jackson.annotation.JsonProperty("notify") WorkflowNotify reminders) {

    public WorkflowDefinition {
        states = states == null ? List.of() : List.copyOf(states);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        deadlines = deadlines == null ? List.of() : List.copyOf(deadlines);
    }

    /** The business document a workflow governs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentSpec(String type, String table, String key, String stateColumn) {
    }

    /** How the synthesized transition routes are mounted on HTTP. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HttpSpec(String basePath) {
    }
}
