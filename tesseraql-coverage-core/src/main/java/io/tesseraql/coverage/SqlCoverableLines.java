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
