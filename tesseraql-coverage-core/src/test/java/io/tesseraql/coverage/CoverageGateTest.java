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
    void failsWhenBranchUnderThreshold() {
        SqlCoverage coverage = new SqlCoverage();
        coverage.record("s.sql", SqlRenderer.render(SQL, Map.of("q", "a")).coverageTrace());

        CoverageGate.Result result = CoverageGate.check(coverage, CoverageThresholds.ofPercent(80));
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0)).contains("s.sql").contains("50%");
    }
}
