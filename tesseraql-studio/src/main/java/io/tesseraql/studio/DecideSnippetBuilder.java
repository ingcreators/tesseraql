package io.tesseraql.studio;

import java.util.List;

/**
 * Generates a route's {@code decide:} YAML block for one declared decision
 * (docs/decision-tables.md) — the validation builder's shape applied to the other shared
 * definition. The output is meant to be copied into a command route (or a workflow
 * transition); it is pure text with no side effect.
 */
public final class DecideSnippetBuilder {

    private DecideSnippetBuilder() {
    }

    /**
     * The {@code decide:} snippet referencing {@code decision}. Every input of the contract is
     * laid out under {@code params:} with a placeholder rather than left out, because a
     * reference must wire the contract <em>exactly</em> — an omission fails the load
     * ({@code TQL-DECISION-4706}), and a snippet that fails the load is worse than no snippet.
     * Returns a {@code # ...} comment when no decision is chosen.
     *
     * @param inputs the decision's input names, in declared order
     * @param dated  whether the decision's table source declares {@code effective:} columns —
     *               a dated lookup may pin its reference instant, so the snippet carries the
     *               {@code effectiveAt:} line as a comment (the default is {@code audit.now})
     */
    public static String generate(String decision, List<String> inputs, boolean dated) {
        if (decision == null || decision.isBlank()) {
            return "# Choose a decision.";
        }
        String name = decision.trim();
        StringBuilder yaml = new StringBuilder("decide:\n  ").append(name).append(":\n");
        yaml.append("    use: ").append(name).append('\n');
        if (inputs != null && !inputs.isEmpty()) {
            yaml.append("    params:\n");
            for (String input : inputs) {
                // The source is the author's to choose; the name is not.
                yaml.append("      ").append(input).append(": params.").append(input)
                        .append('\n');
            }
        }
        if (dated) {
            yaml.append("    # effectiveAt: params.postingDate  (default audit.now)\n");
        }
        return yaml.toString();
    }
}
