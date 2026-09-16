package io.tesseraql.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.sql.SqlRenderer;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoverageGateTest {

    private static final String SQL = """
            select 1
            /*%if q != null */ and q = /* q */ '' /*%end*/""";

    @Test
    void passesWhenBranchFullyCovered() {
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("s.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace());
        coverage.record("s.sql", SqlRenderer.render(SQL, Collections.singletonMap("q", null))
                .coverageTrace());

        CoverageGate.Result result = CoverageGate.check(coverage, CoverageThresholds.ofPercent(80));
        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void gatesItemCoverageKindsByConfiguredThreshold() {
        ItemCoverage routes = new ItemCoverage("route")
                .declare("users.search").declare("users.create").cover("users.search");
        CoverageThresholds thresholds = new CoverageThresholds(0.0, 0.0,
                Map.of("route", 0.8, "scim", 0.8));

        CoverageGate.Result result = CoverageGate.check(new SqlCoverage(),
                java.util.List.of(routes, new ItemCoverage("scim")), thresholds);
        assertThat(result.passed()).isFalse();
        // The empty scim kind passes (nothing declared -> 1.0); the route kind is below 80%.
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0)).contains("route").contains("50%").contains("80%");
    }

    @Test
    void ungatedKindsDoNotFailTheGate() {
        ItemCoverage assertions = new ItemCoverage("assertion").declare("a");
        CoverageGate.Result result = CoverageGate.check(new SqlCoverage(),
                java.util.List.of(assertions), CoverageThresholds.ofPercent(0, 0));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void failsWhenBranchUnderThreshold() {
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("s.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace());

        CoverageGate.Result result = CoverageGate.check(coverage, CoverageThresholds.ofPercent(80));
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0)).contains("s.sql").contains("50%");
    }

    @Test
    void anUntouchedDeclaredFileFailsTheLineGate() {
        // docs/audit-low-leads.md G16: only rendered files used to enter the gate, so deleting the
        // one case that touched the worst-covered file made an 80/80 gate pass.
        java.util.List<io.tesseraql.core.sql.SqlNode> nodes = io.tesseraql.core.sql.Sql2WayParser
                .parse(SQL);
        SqlCoverage coverage = new SqlCoverage();
        coverage.declare("count.sql", SqlCoverableLines.compute(nodes),
                SqlCoverableLines.branchLines(nodes));
        coverage.record("s.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace());
        coverage.record("s.sql", SqlRenderer.render(SQL, Collections.singletonMap("q", null))
                .coverageTrace());

        CoverageGate.Result result = CoverageGate.check(coverage,
                CoverageThresholds.ofPercent(80, 80));
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).containsExactly(
                "count.sql: line coverage 0% < required 80%",
                "count.sql: branch coverage 0% < required 80%");
    }

    @Test
    void aThresholdNamingAKindTheRunDidNotMeasureIsAViolation() {
        // docs/audit-low-leads.md G22: a key the resolver did not know was dropped without a
        // word, so a 0% kind passed a 100% bar and a typo'd key gated nothing.
        ItemCoverage routes = new ItemCoverage("route").declare("users.search")
                .cover("users.search");
        CoverageThresholds thresholds = new CoverageThresholds(0.0, 0.0,
                Map.of("route", 1.0, "sqlLines", 0.8));

        CoverageGate.Result result = CoverageGate.check(new SqlCoverage(),
                java.util.List.of(routes), thresholds);
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).singleElement().asString()
                .contains("coverage.thresholds.sqlLines names a kind this run did not measure")
                .contains("[route]");
    }
}
