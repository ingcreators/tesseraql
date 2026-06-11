package io.tesseraql.test;

import java.util.List;

/**
 * The outcome of running a {@link TestSuite} (design ch. 13, 15).
 *
 * @param results the per-case results, in order
 */
public record TestReport(List<TestResult> results) {

    public TestReport {
        results = List.copyOf(results);
    }

    public long passed() {
        return results.stream().filter(TestResult::passed).count();
    }

    public long failed() {
        return results.size() - passed();
    }

    public boolean allPassed() {
        return failed() == 0;
    }

    /** A single case result. */
    public record TestResult(String name, boolean passed, String message) {

        public static TestResult pass(String name) {
            return new TestResult(name, true, "OK");
        }

        public static TestResult fail(String name, String message) {
            return new TestResult(name, false, message);
        }
    }
}
