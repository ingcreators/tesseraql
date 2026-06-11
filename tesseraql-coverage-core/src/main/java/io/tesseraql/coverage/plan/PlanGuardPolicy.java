package io.tesseraql.coverage.plan;

import java.util.Set;

/**
 * Query plan guard rules (design ch. 46.3, 46.7). A negative limit disables that check.
 *
 * @param maxEstimatedRows  maximum estimated rows for the root node, or -1
 * @param maxPlanCost       maximum total plan cost, or -1
 * @param allowSeqScan      whether sequential/full scans are permitted
 * @param allowedScanTables tables exempt from the no-seq-scan rule (e.g. small code masters)
 */
public record PlanGuardPolicy(
        long maxEstimatedRows,
        double maxPlanCost,
        boolean allowSeqScan,
        Set<String> allowedScanTables) {

    public PlanGuardPolicy {
        allowedScanTables = allowedScanTables == null ? Set.of() : Set.copyOf(allowedScanTables);
    }

    public static PlanGuardPolicy noSeqScan(long maxEstimatedRows) {
        return new PlanGuardPolicy(maxEstimatedRows, -1, false, Set.of());
    }
}
