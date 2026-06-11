package io.tesseraql.coverage.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalized query execution plan node (design ch. 46.6), independent of the database dialect.
 *
 * @param nodeType       the operator, e.g. {@code Seq Scan}, {@code Index Scan}
 * @param relationName   the scanned relation, when applicable
 * @param indexName      the index used, when applicable
 * @param totalCost      the dialect cost estimate (cumulative at this node)
 * @param estimatedRows  the estimated rows produced by this node
 * @param children       child plan nodes
 */
public record QueryPlan(
        String nodeType,
        String relationName,
        String indexName,
        double totalCost,
        long estimatedRows,
        List<QueryPlan> children) {

    public QueryPlan {
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** Returns this node and all descendants, depth-first. */
    public List<QueryPlan> flatten() {
        List<QueryPlan> nodes = new ArrayList<>();
        collect(this, nodes);
        return nodes;
    }

    private static void collect(QueryPlan node, List<QueryPlan> out) {
        out.add(node);
        for (QueryPlan child : node.children()) {
            collect(child, out);
        }
    }
}
