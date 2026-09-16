package io.tesseraql.coverage;

import io.tesseraql.core.sql.SqlNode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes the set of source lines a 2-way SQL template <em>could</em> emit — the denominator for
 * line coverage (design ch. 14). It walks the whole parsed tree, including every conditional branch
 * and loop body, mirroring how {@link io.tesseraql.core.sql.SqlRenderer} records covered lines, so
 * the covered set is always a subset of the coverable set.
 */
public final class SqlCoverableLines {

    private SqlCoverableLines() {
    }

    /** The 1-based source lines that the template could emit across all branches. */
    public static Set<Integer> compute(List<SqlNode> nodes) {
        Set<Integer> lines = new TreeSet<>();
        collect(nodes, lines);
        return lines;
    }

    /**
     * The source lines of every conditional branch the template declares — each {@code if},
     * {@code elseif} and {@code else} arm, nested ones included — the denominator the renderer
     * builds one outcome at a time ({@code recordBranch}), computed statically so a file no case
     * rendered has a branch count to be 0% of (docs/audit-low-leads.md G16).
     */
    public static Set<Integer> branchLines(List<SqlNode> nodes) {
        Set<Integer> lines = new TreeSet<>();
        collectBranches(nodes, lines);
        return lines;
    }

    private static void collectBranches(List<SqlNode> nodes, Set<Integer> out) {
        for (SqlNode node : nodes) {
            if (node instanceof SqlNode.If conditional) {
                for (SqlNode.If.Branch branch : conditional.branches()) {
                    out.add(branch.sourceLine());
                    collectBranches(branch.body(), out);
                }
            } else if (node instanceof SqlNode.For loop) {
                collectBranches(loop.body(), out);
            }
        }
    }

    private static void collect(List<SqlNode> nodes, Set<Integer> out) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Text text -> addTextLines(text, out);
                case SqlNode.Bind bind -> out.add(bind.sourceLine());
                case SqlNode.ListBind listBind -> out.add(listBind.sourceLine());
                case SqlNode.Embedded embedded -> out.add(embedded.sourceLine());
                case SqlNode.If conditional -> conditional.branches()
                        .forEach(branch -> collect(branch.body(), out));
                case SqlNode.For loop -> collect(loop.body(), out);
                case SqlNode.Scope scope -> out.add(scope.sourceLine());
                case SqlNode.Lock lock -> out.add(lock.sourceLine());
                case SqlNode.FilePath filePath -> out.add(filePath.sourceLine());
            }
        }
    }

    /** Adds each non-blank line of a text node, matching the renderer's per-line coverage. */
    private static void addTextLines(SqlNode.Text text, Set<Integer> out) {
        String[] split = text.text().split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            if (!split[i].isBlank()) {
                out.add(text.startLine() + i);
            }
        }
    }
}
