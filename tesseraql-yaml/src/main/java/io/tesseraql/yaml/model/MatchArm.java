package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One arm of a {@link ScopeDefinition}'s ordered {@code match:} list (roadmap Phase 29). When the
 * principal satisfies {@code when}, the arm contributes its predicate:
 *
 * <ul>
 *   <li>{@code apply: all} — the principal sees every row ({@code 1=1});</li>
 *   <li>{@code apply: none} — the principal sees no row ({@code 1=0});</li>
 *   <li>a {@code file} fragment — a 2-way SQL boolean predicate (with {@code params} binds resolved
 *       from the principal) that filters rows.</li>
 * </ul>
 *
 * <p>A principal may match several arms; their predicates compose additively (OR) — a caller sees a
 * row if any of their matching arms would. Matching no arm is deny-by-default ({@code 1=0}).
 *
 * @param when   the principal condition; {@code null} matches every principal (an unconditional arm)
 * @param apply  {@code all} or {@code none}; mutually exclusive with {@code file}
 * @param file   the 2-way SQL predicate fragment, relative to the scope document's directory
 * @param params bind expressions for the fragment, resolved against the request context per call
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchArm(WhenCondition when, String apply, String file, Map<String, String> params) {

    public MatchArm {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Whether this arm grants all rows ({@code apply: all}). */
    public boolean isAll() {
        return "all".equalsIgnoreCase(apply);
    }

    /** Whether this arm denies all rows ({@code apply: none}). */
    public boolean isNone() {
        return "none".equalsIgnoreCase(apply);
    }
}
