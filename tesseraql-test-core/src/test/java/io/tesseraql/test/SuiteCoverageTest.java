package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.SqlTarget;
import io.tesseraql.test.TestSuite.TestCase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteCoverageTest {

    private static final TestSuite SUITE = new TestSuite(List.of(
            new TestCase("asserts-rows", new SqlTarget("a.sql"), null, Map.of(),
                    new Expectation(1, null), null, null, null),
            new TestCase("no-assert", new SqlTarget("b.sql"), null, Map.of(), null, null, null,
                    null),
            new TestCase("contract-case", null, "identity.find-user-by-login", Map.of(),
                    new Expectation(1, null), null, null, null)));

    @Test
    void assertionCoverageCountsCasesThatAssert() {
        ItemCoverage coverage = SuiteCoverage.assertions(List.of(SUITE));
        assertThat(coverage.declared()).hasSize(3);
        assertThat(coverage.covered()).containsExactlyInAnyOrder("asserts-rows", "contract-case");
        assertThat(coverage.ratio()).isCloseTo(2.0 / 3, within(1e-9));
    }

    @Test
    void assertionCoverageCountsUpdateCountAndVerifyAssertions() {
        TestSuite suite = new TestSuite(List.of(
                new TestCase("asserts-update-count", new SqlTarget("write.sql"), null, Map.of(),
                        new Expectation(null, null, 1), null, null, null, null, null),
                new TestCase("asserts-only-in-verify", new SqlTarget("write.sql"), null, Map.of(),
                        null, null, null, null, null,
                        List.of(new TestSuite.VerifyStep(new SqlTarget("read.sql"), Map.of(),
                                new Expectation(1, null)))),
                new TestCase("assert-free-verify", new SqlTarget("write.sql"), null, Map.of(),
                        null, null, null, null, null,
                        List.of(new TestSuite.VerifyStep(new SqlTarget("read.sql"), Map.of(),
                                null)))));
        ItemCoverage coverage = SuiteCoverage.assertions(List.of(suite));
        assertThat(coverage.covered())
                .containsExactlyInAnyOrder("asserts-update-count", "asserts-only-in-verify");
        assertThat(coverage.uncovered()).containsExactly("assert-free-verify");
    }

    @Test
    void contractCoverageTracksExercisedStandardContracts() {
        ItemCoverage coverage = SuiteCoverage.contracts(List.of(SUITE));
        assertThat(coverage.covered()).contains("find-user-by-login");
        assertThat(coverage.uncovered()).contains("create-user", "list-users");
        assertThat(coverage.ratio()).isCloseTo(1.0 / 9, within(1e-9));
    }
}
