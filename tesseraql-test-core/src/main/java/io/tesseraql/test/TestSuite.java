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
public record TestSuite(String version, List<TestCase> tests) {

    public TestSuite {
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    /** Programmatic suites carry the current version implicitly. */
    public TestSuite(List<TestCase> tests) {
        this("tesseraql/v1", tests);
    }

    /**
     * A single test case (design ch. 13.2). Exactly one of {@code sql}, {@code contract},
     * {@code validate}, {@code notify}, {@code messages}, or {@code http} is set.
     *
     * <p>A target is named after the document key it points at, so a case reads like the thing it
     * exercises. That is why {@code httpCall:} became {@code http:} when the binding union
     * absorbed the job step's arm (docs/unified-sources.md decision 12): a target naming a key
     * the surface no longer has would send a reader looking for it.
     *
     * @param name     human-readable case name
     * @param sql      a SQL file target
     * @param contract an Identity SQL Contract name
     * @param params   bind parameters; for a validation, notify, or http case, the execution
     *                 context the declarations see (typically a {@code body:} or {@code job:} map)
     * @param expect   the expectation
     * @param validate a route's validation rules as the target (roadmap Phase 19)
     * @param notifications the {@code notify:} target — a route's or job's notifications
     *                 (roadmap Phase 20; "notify" itself is not a legal record component)
     * @param messages a message-catalog target (roadmap Phase 22)
     * @param httpCall an {@code http:} target — a job's or route's outbound calls (Phase 26)
     * @param decide   a decision-table target (docs/decision-tables.md): the case evaluates one
     *                 declared decision against the params as input values
     * @param verify   read-back steps of a {@code sql} case, run on the case's transaction after
     *                 the target and rolled back with it (only legal with a {@code sql} target)
     * @param principal the request principal the case runs as (docs/data-scoping.md): resolves
     *                 {@code /*%scope … *}{@code /} directives in the target SQL exactly as the
     *                 runtime would, and seeds the {@code principal.*} ambient paths
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestCase(String name, SqlTarget sql, String contract,
            Map<String, Object> params, Expectation expect, ValidateTarget validate,
            @com.fasterxml.jackson.annotation.JsonProperty("notify") NotifyTarget notifications,
            MessagesTarget messages,
            @com.fasterxml.jackson.annotation.JsonProperty("http") HttpCallTarget httpCall,
            DecideTarget decide, List<VerifyStep> verify, PrincipalSpec principal,
            TransitionTarget transition, DispatchTarget dispatch, List<GivenStep> given) {

        public TestCase {
            params = params == null ? Map.of() : Map.copyOf(params);
            verify = verify == null ? List.of() : List.copyOf(verify);
            given = given == null ? List.of() : List.copyOf(given);
        }

        /** Convenience constructor without {@code given} steps (the initial-state shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages, HttpCallTarget httpCall, DecideTarget decide,
                List<VerifyStep> verify, PrincipalSpec principal, TransitionTarget transition,
                DispatchTarget dispatch) {
            this(name, sql, contract, params, expect, validate, notifications, messages, httpCall,
                    decide, verify, principal, transition, dispatch, null);
        }

        /** Convenience constructor without a {@code dispatch} target (the pre-selector shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages, HttpCallTarget httpCall, DecideTarget decide,
                List<VerifyStep> verify, PrincipalSpec principal, TransitionTarget transition) {
            this(name, sql, contract, params, expect, validate, notifications, messages, httpCall,
                    decide, verify, principal, transition, null);
        }

        /** Convenience constructor without a {@code transition} (the pre-workflow shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages, HttpCallTarget httpCall, DecideTarget decide,
                List<VerifyStep> verify, PrincipalSpec principal) {
            this(name, sql, contract, params, expect, validate, notifications, messages, httpCall,
                    decide, verify, principal, null);
        }

        /** Convenience constructor without a {@code principal} (the pre-scoping shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages, HttpCallTarget httpCall, DecideTarget decide,
                List<VerifyStep> verify) {
            this(name, sql, contract, params, expect, validate, notifications, messages, httpCall,
                    decide, verify, null, null);
        }

        /** Convenience constructor without a {@code decide} target (the pre-decisions shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages, HttpCallTarget httpCall, List<VerifyStep> verify) {
            this(name, sql, contract, params, expect, validate, notifications, messages, httpCall,
                    null, verify);
        }

        /** Convenience constructor without {@code verify} steps (the read-only shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages, HttpCallTarget httpCall) {
            this(name, sql, contract, params, expect, validate, notifications, messages, httpCall,
                    null, null);
        }

        /** Convenience constructor without an {@code http-call} target (the pre-Phase-26 shape). */
        public TestCase(String name, SqlTarget sql, String contract, Map<String, Object> params,
                Expectation expect, ValidateTarget validate, NotifyTarget notifications,
                MessagesTarget messages) {
            this(name, sql, contract, params, expect, validate, notifications, messages, null,
                    null, null);
        }
    }

    /**
     * A decision-table target (docs/decision-tables.md): the case evaluates one decision
     * declared under {@code decisions/} against the case's params — the params ARE the input
     * values, no {@code decide:} wiring involved, because the target tests the table, not a
     * reference. The matched row's outputs come back as the case's single row; a miss or a
     * {@code unique} multi-hit comes back as one row carrying {@code code}
     * ({@code TQL-DECISION-4721} / {@code 4720}), so suites assert the no-silent-null contract
     * too. A table-backed decision runs its generated SELECT against the runner's datasource.
     *
     * @param decision    the decision name under {@code decisions/}
     * @param effectiveAt optional reference instant of a dated table source (ISO-8601 instant
     *                    or {@code yyyy-MM-dd HH:mm:ss}); defaults to the runner's clock
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecideTarget(String decision, String effectiveAt) {
    }

    /**
     * A workflow-transition target (docs/approval-workflow.md, docs/testing.md): the case
     * fires one declared transition against the document named by {@code key}, inside the
     * case's always-rolled-back transaction, following the documented pipeline — state
     * legality, {@code decide:} resolution, the guard, the conditional state advance, the
     * command with its scope, and the zero-row contract. The outcome is the case's single
     * row: {@code from}/{@code to} on an advance, or a {@code code} row
     * ({@code TQL-WORKFLOW-3201/3202/3204}, {@code TQL-DECISION-4720/4721}) so a refusal is
     * assertable as data. Task opening, history, notifications, and the task-holder
     * authority check are runtime concerns a rolled-back suite case does not model.
     *
     * @param workflow the workflow id under {@code workflow/}
     * @param key      the business document key
     * @param id       the transition id to fire
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransitionTarget(String workflow, String key, String id) {
    }

    /**
     * A one-action dispatch target (docs/transition-engine.md track C): the case runs the
     * dispatch's member-selection loop — the dispatch-level {@code decide:} once, then each
     * member through the documented transition pipeline, a wrong-state ({@code 3201}) or
     * guard ({@code 3202}) refusal rolling back to its savepoint and falling through — inside
     * the case's always-rolled-back transaction. The outcome is the case's single row: the
     * winner's {@code from}/{@code to} plus {@code transition} (which member fired) and
     * {@code dispatch}, a non-selectable member outcome (its {@code code} row), or the
     * none-held row: {@code code TQL-WORKFLOW-3202} with {@code attempted} naming the members
     * tried, comma-joined.
     *
     * @param workflow the workflow id under {@code workflow/}
     * @param key      the business document key
     * @param id       the dispatch id to run
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DispatchTarget(String workflow, String key, String id) {
    }

    /**
     * A mid-flow fixture step (docs/testing.md): a transition fired — unasserted, but it
     * must advance — before the case's {@code transition:}/{@code dispatch:} target, in the
     * same always-rolled-back transaction, through the same documented pipeline (so stamps,
     * decisions, and state advances are real). A refused step fails the case naming the step
     * and its code. The optional {@code principal} lets the fixture change actors — the
     * requester submits, the manager approves — falling back to the case's principal.
     *
     * @param workflow  the workflow id under {@code workflow/}
     * @param key       the business document key
     * @param id        the transition id to fire
     * @param principal the step's actor, or {@code null} for the case's principal
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GivenStep(String workflow, String key, String id, PrincipalSpec principal) {
    }

    /**
     * The request principal a case runs as (docs/data-scoping.md): the same shape every
     * authentication mechanism produces, so a suite can exercise scope arms and
     * {@code principal.*} ambient paths per posture — one case per role, no tokens involved.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrincipalSpec(String subject, String loginId, List<String> roles,
            List<String> permissions, List<String> groups, Map<String, Object> claims) {

        public PrincipalSpec {
            roles = roles == null ? List.of() : List.copyOf(roles);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            groups = groups == null ? List.of() : List.copyOf(groups);
            claims = claims == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(claims));
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
    public record NotifyTarget(String route, String job, String id, Boolean send) {

        /** Convenience constructor for the evaluate-only shape (pre real-send). */
        public NotifyTarget(String route, String job, String id) {
            this(route, job, id, null);
        }

        /** Whether webhook channels deliver for real against the runner's capture server. */
        public boolean isSend() {
            return Boolean.TRUE.equals(send);
        }
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
    public record HttpCallTarget(String job, String id, String route, Boolean send) {

        /** Convenience constructor for the job-only shape (pre http-source). */
        public HttpCallTarget(String job, String id) {
            this(job, id, null, null);
        }

        /** Convenience constructor for the plan-only shape (pre real-send). */
        public HttpCallTarget(String job, String id, String route) {
            this(job, id, route, null);
        }

        /** Whether the case performs the call for real against the runner's capture server. */
        public boolean isSend() {
            return Boolean.TRUE.equals(send);
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
