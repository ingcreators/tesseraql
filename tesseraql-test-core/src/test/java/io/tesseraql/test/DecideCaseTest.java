package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.test.TestSuite.DecideTarget;
import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code decide:} suite target (docs/decision-tables.md): params are the decision's input
 * values, the matched row's outputs are the case's single row, and a miss comes back as data
 * ({@code code: TQL-DECISION-4721}) so the no-silent-null contract is assertable.
 */
class DecideCaseTest {

    @TempDir
    Path appHome;

    @BeforeEach
    void decisions() throws Exception {
        Path decisions = Files.createDirectories(appHome.resolve("decisions"));
        Files.writeString(decisions.resolve("approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      amount: { type: number, match: between }
                    outputs:
                      assignee: { type: string, enum: [approver-1, cfo-1] }
                    rows:
                      - when: { amount: "> 100000" }
                        out: { assignee: cfo-1 }
                      - out: { assignee: approver-1 }
                  strictTier:
                    inputs:
                      amount: { type: number, match: between }
                    outputs:
                      tier: { type: string }
                    hitPolicy: unique
                    rows:
                      - when: { amount: ">= 100" }
                        out: { tier: high }
                """);
    }

    private static TestCase decide(String name, String decision, int amount,
            Map<String, Object> expectedRow) {
        return new TestCase(name, null, null, Map.of("amount", amount),
                new Expectation(1, List.of(expectedRow)), null, null, null, null,
                new DecideTarget(decision, null), null);
    }

    @Test
    void theMatchedRowsOutputsAreTheCase() {
        TestReport report = new TestRunner(null, appHome).run(new TestSuite(List.of(
                decide("finance lane", "approvalRoute", 250000, Map.of("assignee", "cfo-1")),
                decide("standing approver", "approvalRoute", 100000,
                        Map.of("assignee", "approver-1")))));

        assertThat(report.results()).allSatisfy(result -> assertThat(result.passed())
                .withFailMessage(result.message()).isTrue());
    }

    @Test
    void aMissComesBackAsDataNotAnError() {
        TestReport report = new TestRunner(null, appHome).run(new TestSuite(List.of(
                decide("no tier below the floor", "strictTier", 50,
                        Map.of("code", "TQL-DECISION-4721")))));

        assertThat(report.results()).singleElement()
                .satisfies(result -> assertThat(result.passed())
                        .withFailMessage(result.message()).isTrue());
    }

    @Test
    void anUnknownDecisionFailsTheCaseWithTheReason() {
        TestReport report = new TestRunner(null, appHome).run(new TestSuite(List.of(
                decide("typo", "approvalRoot", 1, Map.of("assignee", "x")))));

        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.passed()).isFalse();
            assertThat(result.message()).contains("approvalRoot");
        });
    }

    @Test
    void decisionCoverageCountsDeclaredAndTargeted() {
        TestSuite suite = new TestSuite(List.of(
                decide("finance lane", "approvalRoute", 250000,
                        Map.of("assignee", "cfo-1"))));

        var coverage = ManifestCoverage.decision(new ManifestLoader().load(appHome),
                List.of(suite));

        assertThat(coverage.declared()).containsExactlyInAnyOrder("approvalRoute", "strictTier");
        assertThat(coverage.covered()).containsExactly("approvalRoute");
    }
}
