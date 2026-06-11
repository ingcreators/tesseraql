package io.tesseraql.coverage.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlServerPlanInspectorTest {

    private static final String NS = "xmlns=\"http://schemas.microsoft.com/sqlserver/2004/07/showplan\"";

    private static String showplan(String physicalOp, String inner, String rows, String cost) {
        return "<ShowPlanXML " + NS + "><BatchSequence><Batch><Statements><StmtSimple><QueryPlan>"
                + "<RelOp PhysicalOp=\"" + physicalOp + "\" EstimateRows=\"" + rows
                + "\" EstimatedTotalSubtreeCost=\"" + cost + "\">" + inner
                + "</RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>";
    }

    @Test
    void tableScanNormalizesToSeqScan() {
        String xml = showplan("Table Scan",
                "<TableScan><Object Table=\"[t]\"/></TableScan>", "1000", "1.5");

        QueryPlan plan = SqlServerPlanInspector.parse(xml);
        assertThat(plan.nodeType()).isEqualTo("Seq Scan");
        assertThat(plan.relationName()).isEqualTo("t");
        assertThat(plan.estimatedRows()).isEqualTo(1000);
        assertThat(PlanGuard.evaluate(plan, PlanGuardPolicy.noSeqScan(10_000)))
                .anyMatch(v -> v.code().toString().equals("TQL-PLAN-1001"));
    }

    @Test
    void indexSeekNormalizesToIndexScan() {
        String xml = showplan("Clustered Index Seek",
                "<IndexScan><Object Table=\"[t]\" Index=\"[PK_t]\"/></IndexScan>", "1", "0.003");

        QueryPlan plan = SqlServerPlanInspector.parse(xml);
        assertThat(plan.nodeType()).isEqualTo("Index Scan");
        assertThat(plan.indexName()).isEqualTo("PK_t");
        assertThat(PlanGuard.evaluate(plan, PlanGuardPolicy.noSeqScan(10_000))).isEmpty();
    }
}
