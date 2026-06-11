package io.tesseraql.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Records which source lines were emitted and which conditional branches were taken while
 * rendering a 2-way SQL variant (design ch. 8.2, 14).
 *
 * <p>Line coverage is the set of source lines that contributed text to the rendered output.
 * Branch coverage records every conditional branch that was evaluated and whether it was taken,
 * which lets downstream coverage reporting compute branch ratios per SQL file.
 */
public final class CoverageTrace {

    /**
     * A conditional branch evaluation outcome.
     *
     * @param sourceLine 1-based line of the {@code if}/{@code elseif}/{@code else} directive
     * @param taken      whether this branch's body was emitted
     */
    public record Branch(int sourceLine, boolean taken) {
    }

    private final Set<Integer> coveredLines = new TreeSet<>();
    private final List<Branch> branches = new ArrayList<>();

    void coverLine(int sourceLine) {
        if (sourceLine > 0) {
            coveredLines.add(sourceLine);
        }
    }

    void recordBranch(int sourceLine, boolean taken) {
        branches.add(new Branch(sourceLine, taken));
    }

    public Set<Integer> coveredLines() {
        return Set.copyOf(coveredLines);
    }

    public List<Branch> branches() {
        return List.copyOf(branches);
    }
}
