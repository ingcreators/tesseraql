package io.tesseraql.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoverageRegressionTest {

    @Test
    void heldOrImprovedCoveragePasses() {
        assertThat(CoverageRegression.check(0.80, 0.70, 0.80, 0.70, 0.0).passed()).isTrue();
        assertThat(CoverageRegression.check(0.90, 0.85, 0.80, 0.70, 0.0).passed()).isTrue();
    }

    @Test
    void aDropInLineCoverageIsARegression() {
        CoverageRegression.Result result = CoverageRegression.check(0.75, 0.70, 0.80, 0.70, 0.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).singleElement().asString()
                .contains("line").contains("80%").contains("75%");
    }

    @Test
    void aDropInBranchCoverageIsARegression() {
        CoverageRegression.Result result = CoverageRegression.check(0.80, 0.60, 0.80, 0.70, 0.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("branch");
    }

    @Test
    void bothMetricsCanRegress() {
        CoverageRegression.Result result = CoverageRegression.check(0.70, 0.50, 0.80, 0.70, 0.0);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(2);
    }

    @Test
    void aDropWithinToleranceIsNotARegression() {
        // 80% -> 79.5% with a 1-point tolerance: within tolerance, holds.
        assertThat(CoverageRegression.check(0.795, 0.70, 0.80, 0.70, 0.01).passed()).isTrue();
        // 80% -> 78% exceeds the 1-point tolerance: a regression.
        assertThat(CoverageRegression.check(0.78, 0.70, 0.80, 0.70, 0.01).passed()).isFalse();
    }

    @Test
    void theToleranceIsNamedInTheMessageWhenNonZero() {
        assertThat(CoverageRegression.check(0.50, 0.70, 0.80, 0.70, 0.05).violations())
                .singleElement().asString().contains("tolerance");
        assertThat(CoverageRegression.check(0.50, 0.70, 0.80, 0.70, 0.0).violations())
                .singleElement().asString().doesNotContain("tolerance");
    }
}
