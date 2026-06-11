package io.tesseraql.coverage.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OraclePlanInspectorTest {

    private static Map<String, Object> row(int id, Integer parent, String op, String options,
            String object, long rows, double cost) {
        return Map.of("id", id, "parent_id", parent == null ? "" : parent,
                "operation", op, "options", options == null ? "" : options,
                "object_name", object == null ? "" : object, "cardinality", rows, "cost", cost);
    }

    @Test
    void normalizesFullTableScan() {
        // SELECT STATEMENT -> TABLE ACCESS FULL on T
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(row(0, null, "SELECT STATEMENT", null, null, 1000, 5.0));
        rows.add(row(1, 0, "TABLE ACCESS", "FULL", "T", 1000, 5.0));

        QueryPlan plan = OraclePlanInspector.parse(rows);
        assertThat(plan.nodeType()).isEqualTo("SELECT STATEMENT");
        assertThat(plan.flatten()).anyMatch(n -> "Seq Scan".equals(n.nodeType())
                && "T".equals(n.relationName()) && n.estimatedRows() == 1000);
        assertThat(PlanGuard.evaluate(plan, PlanGuardPolicy.noSeqScan(10_000)))
                .anyMatch(v -> v.code().toString().equals("TQL-PLAN-1001"));
    }

    @Test
    void normalizesIndexAccess() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(row(0, null, "SELECT STATEMENT", null, null, 1, 2.0));
        rows.add(row(1, 0, "TABLE ACCESS", "BY INDEX ROWID", "T", 1, 2.0));
        rows.add(row(2, 1, "INDEX", "UNIQUE SCAN", "PK_T", 1, 1.0));

        QueryPlan plan = OraclePlanInspector.parse(rows);
        assertThat(plan.flatten()).noneMatch(n -> "Seq Scan".equals(n.nodeType()));
        assertThat(plan.flatten()).anyMatch(n -> "Index Scan".equals(n.nodeType()));
        assertThat(PlanGuard.evaluate(plan, PlanGuardPolicy.noSeqScan(10_000))).isEmpty();
    }
}
