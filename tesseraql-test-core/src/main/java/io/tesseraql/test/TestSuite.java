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
     * {@code validate}, {@code notify}, {@code messages}, or {@code http-call} is set.
     *
     * @param name     human-readable case name
     * @param sql      a SQL file target
     * @param contract an Identity SQL Contract name
     * @param params   bind parameters; for a validation, notify, or http-call case, the execution
     *                 context the declarations see (typically a {@code body:} or {@code job:} map)
     * @param expect   the expectation
     * @param validate a route's validation rules as the target (roadmap Phase 19)
     * @param notifications the {@code notify:} target — a route's or job's notifications
     *                 (roadmap Phase 20; "notify" itself is not a legal record component)
     * @param messages a message-catalog target (roadmap Phase 22)
     * @param httpCall an {@code http-call:} target — a job's outbound REST steps (roadmap Phase 26)
     * @param verify   read-back steps of a {@code sql} case, run on the case's transaction after
     *                 the target and rolled back with it (only legal with a {@code sql} target)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestCase(String name, SqlTarget sql, String contract,
            Map<String, Object> params, Expectation expect, ValidateTarget validate,
            @com.fasterxml.jackson.annotation.JsonProperty("notify") NotifyTarget notifications,
            MessagesTarget messages,
            @com.fasterxml.jackson.annotation.JsonProperty("http-call") HttpCallTarget httpCall,
            List<VerifyStep> verify) {

        public TestCase {
            params = params == null ? Map.of() : Map.copyOf(params);
            verify = verify == null ? List.of() : List.copyOf(verify);
        }

        /** Convenience constructor without {@code verify} steps (the read-only shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages, HttpCallTarget httpCall) {
            this(name, sql, contract, params, expect, validate, notifications, messages, httpCall,
                    null);
        }

        /** Convenience constructor without an {@code http-call} target (the pre-Phase-26 shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages) {
            this(name, sql, contract, params, expect, validate, notifications, messages, null,
                    null);
        }
    }

    /**
     * One read-back of a write {@code sql} case: a query file run on the same connection, inside
     * the same always-rolled-back transaction, after the case's target — so it observes the
     * uncommitted write and rolls back with it. A verify step must return rows (a write file is
     * not a legal read-back).
     *
     * @param sql    the query file to run, app-home relative like a case's target
     * @param params bind parameters for the file
     * @param expect the step's expectation on the returned rows
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyStep(SqlTarget sql, Map<String, Object> params, Expectation expect) {

        public VerifyStep {
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
     * An http-call target (roadmap Phase 26): the case plans a job's {@code http-call:} pipeline
     * steps — or a query route's {@code http:} sources (docs/connectors.md) — against the case's
     * params, without issuing a network request. Each matching step is one row carrying
     * {@code http} (its id or source name), {@code method}, the resolved {@code url} and
     * {@code host}, {@code allowed} (whether the host is in the egress allow-list), and the
     * {@code credential} name. Query bindings resolve exactly as they would at runtime.
     *
     * @param job   the job id whose http-call steps are planned (exactly one of job/route)
     * @param id    optional step id or source name; unset, every declaration is planned
     * @param route the route id whose {@code http:} sources are planned
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HttpCallTarget(String job, String id, String route) {

        /** Convenience constructor for the job-only shape (pre http-source). */
        public HttpCallTarget(String job, String id) {
            this(job, id, null);
        }
    }

    /**
     * A message-catalog target (roadmap Phase 22): the case resolves keys of the app's
     * {@code messages/<locale>.yml} catalogs and asserts on the texts — one row per key, with
     * {@code key}, {@code locale}, and {@code text} columns. Lookup walks the requested tag to
     * its bare language like the runtime does, so a {@code ja-JP} case reads the {@code ja}
     * catalog.
     *
     * @param locale the BCP-47 tag to resolve with
     * @param keys   the keys to resolve; unset, every key visible to the locale (sorted)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessagesTarget(String locale, List<String> keys) {
    }

    /**
     * Assertions on the outcome. A query outcome (result rows) is asserted with {@code rowCount}
     * and {@code rows}; a write outcome (an {@code UPDATE}/{@code INSERT}/{@code DELETE} file's
     * affected-row count) is asserted with {@code updateCount}. Mixing the two fails the case
     * with a message naming the right assertion.
     *
     * @param rowCount    expected number of result rows, or null to skip
     * @param rows        per-row partial matchers: each map's entries must be present in the row
     * @param updateCount expected affected-row count of a write target, or null to skip
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expectation(Integer rowCount, List<Map<String, Object>> rows,
            Integer updateCount) {

        public Expectation {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        /** Convenience constructor without an {@code updateCount} (the read-only shape). */
        public Expectation(Integer rowCount, List<Map<String, Object>> rows) {
            this(rowCount, rows, null);
        }
    }
}
