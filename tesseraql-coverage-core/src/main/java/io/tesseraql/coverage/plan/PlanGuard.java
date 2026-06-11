package io.tesseraql.coverage.plan;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a normalized {@link QueryPlan} against a {@link PlanGuardPolicy} (design ch. 46.7).
 */
public final class PlanGuard {

    private static final TqlErrorCode FULL_SCAN = new TqlErrorCode(TqlDomain.PLAN, 1001);
    private static final TqlErrorCode MAX_ROWS = new TqlErrorCode(TqlDomain.PLAN, 1002);
    private static final TqlErrorCode MAX_COST = new TqlErrorCode(TqlDomain.PLAN, 1003);

    private PlanGuard() {
    }

    /** Returns the rule violations for a plan, empty when it passes. */
    public static List<PlanViolation> evaluate(QueryPlan plan, PlanGuardPolicy policy) {
        List<PlanViolation> violations = new ArrayList<>();

        if (policy.maxEstimatedRows() >= 0 && plan.estimatedRows() > policy.maxEstimatedRows()) {
            violations.add(new PlanViolation(MAX_ROWS, "error",
                    "Estimated rows " + plan.estimatedRows() + " exceed limit " + policy.maxEstimatedRows()));
        }
        if (policy.maxPlanCost() >= 0 && plan.totalCost() > policy.maxPlanCost()) {
            violations.add(new PlanViolation(MAX_COST, "error",
                    "Plan cost " + plan.totalCost() + " exceeds limit " + policy.maxPlanCost()));
        }
        if (!policy.allowSeqScan()) {
            for (QueryPlan node : plan.flatten()) {
                if (isSeqScan(node) && !policy.allowedScanTables().contains(node.relationName())) {
                    violations.add(new PlanViolation(FULL_SCAN, "error",
                            "Sequential scan on '" + node.relationName() + "' is not allowed"));
                }
            }
        }
        return violations;
    }

    private static boolean isSeqScan(QueryPlan node) {
        return node.nodeType() != null && node.nodeType().contains("Seq Scan");
    }
}
