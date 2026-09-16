package io.tesseraql.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.sql.SqlRenderer;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlCoverageTest {

    private static final String SQL = """
            select 1
            /*%if q != null */ and q = /* q */ '' /*%end*/""";

    @Test
    void branchFullyCoveredWhenBothOutcomesSeen() {
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("search.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace());
        coverage.record("search.sql", SqlRenderer.render(SQL, Collections.singletonMap("q", null))
                .coverageTrace());

        SqlCoverageReport report = coverage.report("search.sql");
        assertThat(report.branchCount()).isEqualTo(1);
        assertThat(report.branchOutcomes()).isEqualTo(2);
        assertThat(report.branchRatio()).isEqualTo(1.0);
        assertThat(report.coveredLines()).contains(1);
    }

    @Test
    void branchPartiallyCoveredWithOneOutcome() {
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("search.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace());

        SqlCoverageReport report = coverage.report("search.sql");
        assertThat(report.branchCount()).isEqualTo(1);
        assertThat(report.branchOutcomes()).isEqualTo(1);
        assertThat(report.branchRatio()).isEqualTo(0.5);
    }

    @Test
    void noBranchesIsFullyCovered() {
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("plain.sql", SqlRenderer.render("select 1", Map.of()).coverageTrace());
        assertThat(coverage.report("plain.sql").branchRatio()).isEqualTo(1.0);
    }

    @Test
    void aDeclaredFileReportsZeroUntilACaseRendersIt() {
        // docs/audit-low-leads.md G16: the population is what the manifest binds. A file
        // declared before the run and touched by no case is 0% line and 0% branch, not absent.
        java.util.List<io.tesseraql.core.sql.SqlNode> nodes = io.tesseraql.core.sql.Sql2WayParser
                .parse(SQL);
        SqlCoverage coverage = new SqlCoverage();
        coverage.declare("search.sql", SqlCoverableLines.compute(nodes),
                SqlCoverableLines.branchLines(nodes));

        SqlCoverageReport untouched = coverage.report("search.sql");
        assertThat(untouched.coverableLineCount()).isEqualTo(2);
        assertThat(untouched.lineCount()).isZero();
        assertThat(untouched.lineRatio()).isZero();
        assertThat(untouched.branchCount()).isEqualTo(1);
        assertThat(untouched.branchOutcomes()).isZero();
        assertThat(untouched.branchRatio()).isZero();
        assertThat(coverage.reports()).containsOnlyKeys("search.sql");

        // A later render fills the same entry in; the branch keys are the renderer's own lines.
        coverage.record("search.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace(),
                SqlCoverableLines.compute(nodes));
        SqlCoverageReport rendered = coverage.report("search.sql");
        assertThat(rendered.lineRatio()).isEqualTo(1.0);
        assertThat(rendered.branchCount()).isEqualTo(1);
        assertThat(rendered.branchRatio()).isEqualTo(0.5);
    }

    @Test
    void theStaticBranchLinesAreTheRenderersBranchKeys() {
        String sql = """
                select 1
                /*%if a */ and a = 1
                /*%elseif b */ and b = 1
                /*%else */ and c = 1
                /*%end*/
                /*%for x : xs */ /*%if x */ or x = /* x */1 /*%end*/ /*%end*/""";
        java.util.List<io.tesseraql.core.sql.SqlNode> nodes = io.tesseraql.core.sql.Sql2WayParser
                .parse(sql);

        assertThat(SqlCoverableLines.branchLines(nodes)).containsExactly(2, 3, 4, 6);
    }
}
