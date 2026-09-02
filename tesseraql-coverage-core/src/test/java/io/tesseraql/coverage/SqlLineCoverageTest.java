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
    void aLockDirectiveLineIsCoverableAndASeededRenderCoversIt() {
        // renderLock covers its line, so the line has to be counted as coverable — otherwise a
        // covered-but-uncoverable line pushes the ratio above 1.
        List<SqlNode> nodes = Sql2WayParser.parse("""
                update t set a = 1
                 where id = /* id */ 0 and /*%lock*/ (1=1)""");
        Set<Integer> coverable = SqlCoverableLines.compute(nodes);
        assertThat(coverable).containsExactly(1, 2);

        SqlCoverage coverage = new SqlCoverage();
        coverage.record("update.sql", SqlRenderer.render(nodes,
                Map.of("id", 1L, io.tesseraql.core.sql.LockBinding.PARAM,
                        new io.tesseraql.core.sql.LockBinding("version", 3L, false)))
                .coverageTrace(), coverable);

        assertThat(coverage.report("update.sql").lineRatio()).isEqualTo(1.0);
    }

    @Test
    void lineRatioReflectsUncoveredConditionalBody() {
        List<SqlNode> nodes = Sql2WayParser.parse(SQL);
        Set<Integer> coverable = SqlCoverableLines.compute(nodes);

        SqlCoverage coverage = new SqlCoverage();
        coverage.record("search.sql",
                SqlRenderer.render(nodes, Collections.singletonMap("q", null)).coverageTrace(),
                coverable);

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

        CoverageGate.Result strict = CoverageGate.check(coverage,
                CoverageThresholds.ofPercent(80, 80));
        assertThat(strict.passed()).isFalse();
        assertThat(strict.violations()).anyMatch(v -> v.contains("line coverage"))
                .anyMatch(v -> v.contains("branch coverage"));

        // Branch-only thresholds do not gate line coverage.
        CoverageGate.Result branchOnly = CoverageGate.check(coverage,
                CoverageThresholds.ofPercent(40));
        assertThat(branchOnly.passed()).isTrue();
    }
}
