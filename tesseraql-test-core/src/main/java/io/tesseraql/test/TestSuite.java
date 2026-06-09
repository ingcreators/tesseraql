package io.tesseraql.test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * A declarative test suite (design ch. 13). Each case runs a SQL file or an Identity SQL Contract
 * with parameters and asserts on the returned rows.
 *
 * @param tests the test cases
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestSuite(List<TestCase> tests) {

    public TestSuite {
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    /**
     * A single test case (design ch. 13.2). Exactly one of {@code sql} or {@code contract} is set.
     *
     * @param name     human-readable case name
     * @param sql      a SQL file target
     * @param contract an Identity SQL Contract name
     * @param params   bind parameters
     * @param expect   the expectation
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestCase(String name, SqlTarget sql, String contract,
            Map<String, Object> params, Expectation expect) {

        public TestCase {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** A SQL file target. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SqlTarget(String file) {
    }

    /**
     * Assertions on the result rows.
     *
     * @param rowCount expected number of rows, or null to skip
     * @param rows     per-row partial matchers: each map's entries must be present in the row
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expectation(Integer rowCount, List<Map<String, Object>> rows) {

        public Expectation {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}
