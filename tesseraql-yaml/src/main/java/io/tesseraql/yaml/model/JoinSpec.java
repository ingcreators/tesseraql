package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The approval join (docs/approval-workflow.md, "The approval join"): several stamps, one
 * advance.
 *
 * <p>"Both accounting and purchasing must approve before issue" is an AND-join, and the surface
 * deliberately has no fork/join. The pattern is expressible without one — a self-loop transition
 * per approver, each stamping its column, and an advance guarded on every stamp — and it works.
 * What it does not do is tell anything: an invariant maintained by hand, invisible to the lints.
 * Nothing checked that every stamp column had a stamping transition, that the rework transition
 * cleared all of them, or that the advance guard named the full set, and each of those is a
 * silent logic bug when missed.
 *
 * <p>So this introduces no control flow. It declares the set, which lets the guard be
 * synthesized instead of hand-written and lets lint prove the three invariants
 * ({@code TQL-WORKFLOW-3117..3119}). Auto-advance when the last stamp lands is deliberately not
 * offered: firing a transition from inside another transition <em>is</em> new control flow. The
 * last approver advances through a {@code dispatch:} pair, as today.
 *
 * @param stamps the document columns that must all be set for the transition to be legal
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JoinSpec(List<String> stamps) {

    public JoinSpec {
        stamps = stamps == null ? List.of() : List.copyOf(stamps);
    }

    /**
     * The guard this join stands for: every listed column present on the document.
     *
     * <p>Synthesized rather than hand-written, because the hand-written version is exactly the
     * invariant that drifts — an approver added to the set and forgotten in the guard is the
     * bug this declaration exists to make impossible.
     */
    public String guardExpression() {
        return stamps.stream()
                .map(column -> "document." + column + " != null")
                .reduce((left, right) -> left + " && " + right)
                .orElse("true");
    }

    public boolean isEmpty() {
        return stamps.isEmpty();
    }
}
