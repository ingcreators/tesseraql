package io.tesseraql.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlLineCoverageTest {

    private static final String SQL = """
            select 1
            /*%if q != null */ and q = /* q */ '' /*%end*/""";

    @Test
    void coverableLinesIncludeConditionalBody() {
        Set<Integer> coverable = SqlCoverableLines.compute(Sql2WayParser.parse(SQL));
        assertThat(coverable).containsExactly(1, 2);
    }

    @Test
    void lineRatioReflectsUncoveredConditionalBody() {
        List<SqlNode> nodes = Sql2WayParser.parse(SQL);
        Set<Integer> coverable = SqlCoverableLines.compute(nodes);

        SqlCoverage coverage = new SqlCoverage();
        coverage.record("search.sql",
                SqlRenderer.render(nodes, Collections.singletonMap("q", null)).coverageTrace(), coverable);

        SqlCoverageReport report = coverage.report("search.sql");
        assertThat(report.coverableLineCount()).isEqualTo(2);
        assertThat(report.lineRatio()).isEqualTo(0.5);

        // Covering the conditional body brings line coverage to 100%.
        coverage.record("search.sql",
                SqlRenderer.render(nodes, Map.of("q", "a")).coverageTrace(), coverable);
        assertThat(coverage.report("search.sql").lineRatio()).isEqualTo(1.0);
    }

    @Test
    void lineRatioIsOneWhenDenominatorUnknown() {
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("search.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace());
        assertThat(coverage.report("search.sql").lineRatio()).isEqualTo(1.0);
    }

    @Test
    void gateFlagsLineAndBranchShortfalls() {
        List<SqlNode> nodes = Sql2WayParser.parse(SQL);
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("search.sql",
                SqlRenderer.render(nodes, Collections.singletonMap("q", null)).coverageTrace(),
                SqlCoverableLines.compute(nodes));

        CoverageGate.Result strict = CoverageGate.check(coverage, CoverageThresholds.ofPercent(80, 80));
        assertThat(strict.passed()).isFalse();
        assertThat(strict.violations()).anyMatch(v -> v.contains("line coverage"))
                .anyMatch(v -> v.contains("branch coverage"));

        // Branch-only thresholds do not gate line coverage.
        CoverageGate.Result branchOnly = CoverageGate.check(coverage, CoverageThresholds.ofPercent(40));
        assertThat(branchOnly.passed()).isTrue();
    }
}
