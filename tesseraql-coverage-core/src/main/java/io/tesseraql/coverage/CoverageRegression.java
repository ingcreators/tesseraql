package io.tesseraql.coverage;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a run's aggregate SQL coverage against the previous run's (Studio backlog: the coverage
 * regression gate). Where {@link CoverageGate} enforces an <em>absolute</em> minimum, this enforces
 * a <em>relative</em> one: coverage must not fall below the previous run's by more than a tolerance.
 * It is the CI guard that catches a pull request quietly dropping coverage even while every absolute
 * threshold still passes.
 *
 * <p>Pure and dependency-free — it compares ratios in {@code [0,1]} — so the build ({@code report}
 * goal) and the CLI ({@code tesseraql test --report}) share one definition of a regression.
 */
public final class CoverageRegression {

    private CoverageRegression() {
    }

    /**
     * Compares the current run's aggregate SQL line and branch coverage to the previous run's,
     * allowing a drop of up to {@code tolerance} (a ratio in {@code [0,1]}). A metric that falls by
     * more than the tolerance is a regression; equal or improved coverage passes.
     *
     * @param currentLine    this run's aggregate SQL line ratio
     * @param currentBranch  this run's aggregate SQL branch ratio
     * @param previousLine   the previous run's aggregate SQL line ratio
     * @param previousBranch the previous run's aggregate SQL branch ratio
     * @param tolerance      the allowed drop before a fall counts as a regression
     */
    public static Result check(double currentLine, double currentBranch, double previousLine,
            double previousBranch, double tolerance) {
        List<String> violations = new ArrayList<>();
        if (currentLine < previousLine - tolerance) {
            violations.add(message("line", previousLine, currentLine, tolerance));
        }
        if (currentBranch < previousBranch - tolerance) {
            violations.add(message("branch", previousBranch, currentBranch, tolerance));
        }
        return new Result(violations.isEmpty(), violations);
    }

    private static String message(String metric, double previous, double current,
            double tolerance) {
        String allowance = tolerance > 0
                ? String.format(" beyond the %.0f%% tolerance", tolerance * 100)
                : "";
        return String.format("SQL %s coverage regressed %.0f%% -> %.0f%%%s", metric, previous * 100,
                current * 100, allowance);
    }

    /** Regression-gate outcome: whether coverage held, and the per-metric regression messages. */
    public record Result(boolean passed, List<String> violations) {

        public Result {
            violations = List.copyOf(violations);
        }
    }
}
