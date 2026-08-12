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
 * its {@code params} map any further binds the command SQL needs to context expressions
 * ({@code body.*}, {@code document.*}, {@code path.*}), exactly like a {@code command-json} step.
 *
 * <p>{@code command:} is a {@code { file:, params: }} reference, the one spelling every
 * role-typed SQL reference on the surface shares (docs/unified-sources.md decision 14) — it was
 * the surface's only bare-string statement reference, with its binds one level out.
 *
 * @param id       the transition id (unique within the workflow)
 * @param from     the state the document must be in
 * @param to       the state the document moves to
 * @param guard    the legality guard — a whitelist expression or a 2-way SQL query file
 *                 ({@link GuardSpec}) — or {@code null} for an unconditional transition
 * @param command  the 2-way SQL command (relative to the workflow document), or {@code null}
 * @param assign   the assignee-resolution contract (slice 2), or {@code null}
 * @param security an optional per-transition security override of the workflow default
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransitionSpec(String id, String from, String to, GuardSpec guard,
        SqlRef command, AssignSpec assign, SecuritySpec security,
        // Decision-table references evaluated before the guard (docs/decision-tables.md
        // "Acting on the result"): the guard selects among declared transitions by
        // decision.<alias>.<output>, and assignee-resolution SQL binds the outputs.
        Map<String, DecisionUse> decide,
        // Decision stamps (docs/workflow-expressiveness.md slice 2): document columns the
        // engine persists in the transition's transaction, before the author command —
        // values are decision.*/document.*/principal.* paths, literals, or null (a rework
        // transition's declared clearing). Nulls are legal values, so no Map.copyOf.
        Map<String, Object> stamp) {

    /** The command's file, and the binds it needs. */
    public String commandFile() {
        return command == null ? null : command.file();
    }

    /** The command's declared binds, or an empty map. */
    public Map<String, String> params() {
        return command == null ? Map.of() : command.params();
    }

    public TransitionSpec {
        decide = decide == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(decide));
        stamp = stamp == null
                ? java.util.Collections.emptyMap()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(stamp));
    }
}
