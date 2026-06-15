package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A {@code kind: scope} document under {@code scope/} (roadmap Phase 29 — organizational data
 * scoping): a named, reusable row-level predicate derived from the request principal, the row-level
 * complement to multi-tenancy.
 *
 * <p>A query opts in by marking the injection site with a {@code /*%scope <id> on <alias> *}{@code /}
 * directive; at execution the runtime evaluates the {@code match} arms against the principal and
 * renders a parameterized predicate (the matching arms combined with OR; no match is
 * deny-by-default). The fragment files referenced by the arms are plain 2-way SQL, runnable in a SQL
 * tool.
 *
 * @param version the DSL version, e.g. {@code tesseraql/v1}
 * @param id      the scope id named by {@code /*%scope id *}{@code /} directives
 * @param kind    always {@code scope}
 * @param match   the ordered match arms (see {@link MatchArm})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScopeDefinition(String version, String id, String kind, List<MatchArm> match) {

    public ScopeDefinition {
        match = match == null ? List.of() : List.copyOf(match);
    }
}
