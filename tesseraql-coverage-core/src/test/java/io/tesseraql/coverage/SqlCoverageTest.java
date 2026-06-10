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
}
