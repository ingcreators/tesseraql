package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One transition of a {@link WorkflowDefinition} (roadmap Phase 28): it moves a document from
 * {@code from} to {@code to} when the {@code guard} holds, running {@code command} in the
 * transition's transaction.
 *
 * <p>The {@code guard} is a whitelist-only core expression over {@code document.*}/{@code task.*}/
 * {@code principal.*} — it checks state-machine legality (a falsy guard is a {@code 422}). Row
 * authority is a separate concern, enforced by a {@code /*%scope%/} directive in the command's
 * {@code UPDATE} (Phase 29) or the transition's {@code security.policy}. The {@code assign}
 * resolution is parsed and linted in slice 1 but consumed in slice 2.
 *
 * <p>The command always receives the document key as the {@code key} bind ({@code /* key *}{@code /});
 * {@code params} maps any further binds the command SQL needs to context expressions
 * ({@code body.*}, {@code document.*}, {@code path.*}), exactly like a {@code command-json} step.
 *
 * @param id       the transition id (unique within the workflow)
 * @param from     the state the document must be in
 * @param to       the state the document moves to
 * @param guard    the legality expression, or {@code null} for an unconditional transition
 * @param command  the 2-way SQL command file (relative to the workflow document), or {@code null}
 * @param params   bind expressions for the command SQL, resolved against the request context
 * @param assign   the assignee-resolution contract (slice 2), or {@code null}
 * @param security an optional per-transition security override of the workflow default
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransitionSpec(String id, String from, String to, String guard, String command,
        Map<String, String> params, AssignSpec assign, SecuritySpec security) {

    public TransitionSpec {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
