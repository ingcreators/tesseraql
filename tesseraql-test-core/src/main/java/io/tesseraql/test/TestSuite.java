package io.tesseraql.test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * A declarative test suite (design ch. 13). Each case runs a SQL file, an Identity SQL Contract,
 * a route's validation rules (roadmap Phase 19), or a route's/job's notifications (roadmap
 * Phase 20) with parameters and asserts on the returned rows — for a validation case the
 * violations are the rows, for a notify case the fired notifications are.
 *
 * @param tests the test cases
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestSuite(List<TestCase> tests) {

    public TestSuite {
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    /**
     * A single test case (design ch. 13.2). Exactly one of {@code sql}, {@code contract},
     * {@code validate}, or {@code notify} is set.
     *
     * @param name     human-readable case name
     * @param sql      a SQL file target
     * @param contract an Identity SQL Contract name
     * @param params   bind parameters; for a validation or notify case, the execution context
     *                 the declarations see (typically a {@code body:} map)
     * @param expect   the expectation
     * @param validate a route's validation rules as the target (roadmap Phase 19)
     * @param notifications the {@code notify:} target — a route's or job's notifications
     *                 (roadmap Phase 20; "notify" itself is not a legal record component)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestCase(String name, SqlTarget sql, String contract,
            Map<String, Object> params, Expectation expect, ValidateTarget validate,
            @com.fasterxml.jackson.annotation.JsonProperty("notify") NotifyTarget notifications) {

        public TestCase {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** A SQL file target. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SqlTarget(String file) {
    }

    /**
     * A validation-rule target (roadmap Phase 19): the case evaluates the route's
     * {@code validate:} block against the case's params and asserts on the returned violations.
     *
     * @param route the route id whose rules are evaluated
     * @param rule  optional rule id; unset, every rule of the route's block is evaluated
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidateTarget(String route, String rule) {
    }

    /**
     * A notification target (roadmap Phase 20): the case evaluates a route's {@code notify:}
     * block or a job's {@code notify:} pipeline steps against the case's params, without
     * touching SMTP or HTTP. Each notification that fires is one row carrying {@code notify}
     * (its id), {@code channel}, {@code source}, and the resolved payload columns.
     *
     * @param route the route id whose notifications are evaluated (exactly one of route/job)
     * @param job   the job id whose notify steps are evaluated
     * @param id    optional notification/step id; unset, every declaration is evaluated
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotifyTarget(String route, String job, String id) {
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
