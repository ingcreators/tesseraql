package io.tesseraql.test;

import io.tesseraql.coverage.SqlCoverableLines;
import io.tesseraql.test.TestReport.TestResult;
import io.tesseraql.test.TestSuite.TestCase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two workflow case kinds — {@code transition} and {@code dispatch} — together with the
 * {@code given:} fixture steps they share: all three fire the documented pipeline through the
 * {@code TransitionExecutor} inside the case's always-rolled-back transaction.
 */
final class WorkflowCases {

    private final SuiteContext context;
    private final SqlCases sql;

    WorkflowCases(SuiteContext context, SqlCases sql) {
        this.context = context;
        this.sql = sql;
    }

    /**
     * Fires one workflow transition (docs/approval-workflow.md's documented pipeline) inside
     * an always-rolled-back transaction, and returns the outcome as the case's single row —
     * an advance as {@code from}/{@code to}, a refusal as a {@code code} row
     * ({@code TQL-WORKFLOW-3201} not in the from-state, {@code 3202} guard,
     * {@code 3204} zero-row command — the row-authority contract), so a suite asserts the
     * state machine per posture the same way {@code decide:} asserts a table. Decisions
     * resolve after the document binds and before the guard, exactly as at runtime; the
     * command renders with the case principal's scope context. Task opening, history,
     * notifications, and task-holder authority are runtime concerns a rolled-back case does
     * not model.
     */
    TestResult runTransition(TestCase test) {
        TestSuite.TransitionTarget target = test.transition();
        io.tesseraql.yaml.manifest.WorkflowFile workflowFile = context.manifest().workflows()
                .stream()
                .filter(w -> target.workflow().equals(w.definition().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Test '" + test.name()
                        + "' targets unknown workflow '" + target.workflow() + "'"));
        io.tesseraql.yaml.model.WorkflowDefinition def = workflowFile.definition();
        io.tesseraql.yaml.model.TransitionSpec transition = def.transitions().stream()
                .filter(t -> target.id().equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Test '" + test.name()
                        + "' targets unknown transition '" + target.id() + "' of workflow '"
                        + target.workflow() + "'"));
        boolean managed = "managed".equals(def.mode() != null
                ? def.mode()
                : context.manifest().config().getString("tesseraql.workflow.mode").orElse("app"));
        String table = def.document().table();
        String keyColumn = def.document().key();
        try (Connection connection = context.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                String given = runGiven(connection, test);
                if (given != null) {
                    return TestResult.fail(test.name(), given);
                }
                List<Map<String, Object>> rows = List.of(fireTransition(connection, def,
                        transition, target, managed, table, keyColumn, test,
                        test.principal(), null));
                String failure = Expectations.assertOutcome(test.expect(),
                        new SqlOutcome(rows, null));
                for (int i = 0; failure == null && i < test.verify().size(); i++) {
                    failure = sql.runVerifyStep(connection, test.verify().get(i), i,
                            test.principal());
                }
                return failure == null
                        ? TestResult.pass(test.name())
                        : TestResult.fail(test.name(), failure);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Transition failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Runs a {@code dispatch} case (docs/transition-engine.md track C): the member-selection
     * loop inside the case's always-rolled-back transaction, each refused attempt rolled back
     * to its savepoint so the next member (and the verify steps) see what the first one saw.
     */
    TestResult runDispatch(TestCase test) {
        TestSuite.DispatchTarget target = test.dispatch();
        io.tesseraql.yaml.manifest.WorkflowFile workflowFile = context.manifest().workflows()
                .stream()
                .filter(w -> target.workflow().equals(w.definition().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Test '" + test.name()
                        + "' targets unknown workflow '" + target.workflow() + "'"));
        io.tesseraql.yaml.model.WorkflowDefinition def = workflowFile.definition();
        io.tesseraql.yaml.model.DispatchSpec dispatch = def.dispatch().stream()
                .filter(d -> target.id().equals(d.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Test '" + test.name()
                        + "' targets unknown dispatch '" + target.id() + "' of workflow '"
                        + target.workflow() + "'"));
        boolean managed = "managed".equals(def.mode() != null
                ? def.mode()
                : context.manifest().config().getString("tesseraql.workflow.mode").orElse("app"));
        String table = def.document().table();
        String keyColumn = def.document().key();
        try (Connection connection = context.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                String given = runGiven(connection, test);
                if (given != null) {
                    return TestResult.fail(test.name(), given);
                }
                List<Map<String, Object>> rows = List.of(fireDispatch(connection, def, dispatch,
                        target, managed, table, keyColumn, test));
                String failure = Expectations.assertOutcome(test.expect(),
                        new SqlOutcome(rows, null));
                for (int i = 0; failure == null && i < test.verify().size(); i++) {
                    failure = sql.runVerifyStep(connection, test.verify().get(i), i,
                            test.principal());
                }
                return failure == null
                        ? TestResult.pass(test.name())
                        : TestResult.fail(test.name(), failure);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Dispatch failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Fires the case's {@code given:} fixture steps (docs/testing.md) on the case's
     * connection — each through the full documented pipeline, so stamps and state advances
     * are real — and returns a failure message when a step refuses instead of advancing.
     * Steps may name their own actor; the case's principal is the default.
     */
    private String runGiven(Connection connection, TestCase test) throws java.sql.SQLException {
        for (int i = 0; i < test.given().size(); i++) {
            TestSuite.GivenStep step = test.given().get(i);
            io.tesseraql.yaml.manifest.WorkflowFile workflowFile = context.manifest().workflows()
                    .stream()
                    .filter(w -> step.workflow() != null
                            && step.workflow().equals(w.definition().id()))
                    .findFirst()
                    .orElse(null);
            if (workflowFile == null) {
                return "given step " + (i + 1) + " targets unknown workflow '"
                        + step.workflow() + "'";
            }
            io.tesseraql.yaml.model.WorkflowDefinition def = workflowFile.definition();
            io.tesseraql.yaml.model.TransitionSpec transition = def.transitions().stream()
                    .filter(t -> step.id() != null && step.id().equals(t.id()))
                    .findFirst()
                    .orElse(null);
            if (transition == null) {
                return "given step " + (i + 1) + " targets unknown transition '" + step.id()
                        + "' of workflow '" + step.workflow() + "'";
            }
            boolean managed = "managed".equals(def.mode() != null
                    ? def.mode()
                    : context.manifest().config().getString("tesseraql.workflow.mode")
                            .orElse("app"));
            Map<String, Object> outcome = fireTransition(connection, def, transition,
                    new TestSuite.TransitionTarget(step.workflow(), step.key(), step.id()),
                    managed, def.document().table(), def.document().key(), test,
                    step.principal() != null ? step.principal() : test.principal(), null);
            if (outcome.get("to") == null) {
                return "given step " + (i + 1) + " (" + step.workflow() + "." + step.id()
                        + " on '" + step.key() + "') refused: " + outcome;
            }
        }
        return null;
    }

    /** The selection loop itself; returns the outcome row (winner, code, or none-held). */
    private Map<String, Object> fireDispatch(Connection connection,
            io.tesseraql.yaml.model.WorkflowDefinition def,
            io.tesseraql.yaml.model.DispatchSpec dispatch, TestSuite.DispatchTarget target,
            boolean managed, String table, String keyColumn, TestCase test)
            throws java.sql.SQLException {
        // The dispatch-level decide: once, after the document binds, before the loop.
        Map<String, Object> inherited = null;
        if (!dispatch.decide().isEmpty()) {
            String vendor = context.vendor();
            Map<String, Object> bindings = new LinkedHashMap<>(
                    SuiteContext.withPrincipal(test.params(), test.principal()));
            bindings.put("key", target.key());
            Map<String, Object> document = selectDocument(connection, table, keyColumn,
                    target.key());
            bindings.put("document", document == null ? Map.of() : document);
            try {
                inherited = new LinkedHashMap<>(io.tesseraql.yaml.decision.DecisionSets
                        .compileUses(dispatch.decide(), vendor)
                        .evaluate(bindings, connection, suiteStatements()));
            } catch (io.tesseraql.core.error.TqlException miss) {
                Map<String, Object> outcome = new LinkedHashMap<>();
                outcome.put("workflow", def.id());
                outcome.put("dispatch", dispatch.id());
                outcome.put("code", miss.code().toString());
                return outcome;
            }
        }
        List<String> attempted = new ArrayList<>();
        for (String memberId : dispatch.oneOf()) {
            io.tesseraql.yaml.model.TransitionSpec member = def.transitions().stream()
                    .filter(t -> memberId.equals(t.id())).findFirst().orElse(null);
            if (member == null) {
                continue;
            }
            java.sql.Savepoint savepoint = connection.setSavepoint();
            Map<String, Object> outcome = fireTransition(connection, def, member,
                    new TestSuite.TransitionTarget(target.workflow(), target.key(), memberId),
                    managed, table, keyColumn, test, test.principal(), inherited);
            Object code = outcome.get("code");
            boolean fellThrough = "TQL-WORKFLOW-3201".equals(code)
                    || "TQL-WORKFLOW-3202".equals(code);
            if (fellThrough) {
                connection.rollback(savepoint);
                attempted.add(memberId);
                continue;
            }
            if (code != null) {
                // A non-selectable refusal (3204, a decision miss) is the outcome, but its
                // partial writes must not leak into the verify steps - the runtime rolls
                // that attempt's own transaction back.
                connection.rollback(savepoint);
            }
            outcome.put("dispatch", dispatch.id());
            return outcome;
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("workflow", def.id());
        outcome.put("dispatch", dispatch.id());
        outcome.put("code", "TQL-WORKFLOW-3202");
        outcome.put("attempted", String.join(",", attempted));
        return outcome;
    }

    /**
     * Fires the transition through the {@code TransitionExecutor}
     * (docs/transition-engine.md) — the same pipeline implementation the synthesized
     * routes run, in the suite's rolled-back transaction — and shapes the outcome row
     * (advance or coded refusal). Task authority and tasks/history/notify stay out of
     * scope, as documented in docs/testing.md.
     */
    private Map<String, Object> fireTransition(Connection connection,
            io.tesseraql.yaml.model.WorkflowDefinition def,
            io.tesseraql.yaml.model.TransitionSpec transition,
            TestSuite.TransitionTarget target, boolean managed, String table, String keyColumn,
            TestCase test, TestSuite.PrincipalSpec principal,
            Map<String, Object> inheritedDecisions)
            throws java.sql.SQLException {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("workflow", def.id());
        outcome.put("transition", transition.id());

        // The existence pre-check shapes the outcome's `from`; the pipeline itself —
        // document load, decide, legality, guard, advance, stamps — is the executor's.
        Map<String, Object> document = selectDocument(connection, table, keyColumn,
                target.key());
        if (document == null) {
            outcome.put("code", "TQL-WORKFLOW-3201");
            outcome.put("from", null);
            return outcome;
        }
        io.tesseraql.core.workflow.WorkflowStore store = managed
                ? new io.tesseraql.operations.workflow.JdbcWorkflowStore(context.dataSource())
                : new io.tesseraql.yaml.workflow.ColumnWorkflowStore(table, keyColumn,
                        def.document().stateColumn());
        String current = store.currentState(connection, def.document().type(), target.key());
        outcome.put("from", current != null ? current : def.initial());

        Map<String, Object> bindings = new LinkedHashMap<>(
                SuiteContext.withPrincipal(test.params(), principal));
        bindings.put("key", target.key());
        String actor = principal == null || principal.loginId() == null
                ? (principal == null ? "suite" : principal.subject())
                : principal.loginId();
        bindings.putIfAbsent("audit", Map.of("user", actor == null ? "suite" : actor));
        if (inheritedDecisions != null) {
            // The dispatch-level decide: results (docs/transition-engine.md track B) — a
            // member with its own decide: overwrites them inside the executor.
            bindings.put(io.tesseraql.core.sql.AmbientBinds.DECISION, inheritedDecisions);
        }

        String vendor = context.vendor();
        io.tesseraql.yaml.workflow.TransitionExecutor.CompiledTransition compiled = io.tesseraql.yaml.workflow.TransitionExecutor
                .compile(def, transition, managed,
                        vendor, workflowDir(def));
        // Guard-file SQL joins route coverage through the executor's observer.
        Path guardFile = transition.guard() == null || transition.guard().file() == null
                ? null
                : workflowDir(def).resolve(transition.guard().file());
        io.tesseraql.yaml.workflow.TransitionExecutor.GuardSqlObserver observer = guardFile == null
                || context.coverage() == null
                        ? null
                        : (nodes, bound) -> context.coverage().record(
                                context.sqlId(guardFile),
                                bound.coverageTrace(), SqlCoverableLines.compute(nodes));
        try {
            io.tesseraql.yaml.workflow.TransitionExecutor.Session session = io.tesseraql.yaml.workflow.TransitionExecutor
                    .begin(connection, compiled,
                            new io.tesseraql.yaml.workflow.TransitionExecutor.Collaborators(
                                    store, null, context.scopeResolver(),
                                    test.principal() == null
                                            ? null
                                            : test.principal().subject(),
                                    List.of(), suiteStatements(), observer),
                            target.key(), bindings);
            session.advance(connection, bindings);
            if (transition.command() != null && !transition.commandFile().isBlank()) {
                SqlOutcome result = context.executeSql(connection,
                        workflowDir(def).resolve(transition.commandFile()), bindings);
                session.enforceCommandRows(result.updateCount() != null,
                        result.updateCount() == null ? 0 : result.updateCount());
            }
        } catch (io.tesseraql.core.error.TqlException refusal) {
            // Every pipeline refusal is typed: the documented code becomes the row, and a
            // SQL guard file's declared refusal code rides the `guard` column.
            outcome.put("code", refusal.code().toString());
            if (refusal.details() != null && refusal.details().get("code") != null) {
                outcome.put("guard", refusal.details().get("code"));
            }
            return outcome;
        }
        outcome.put("to", transition.to());
        return outcome;
    }

    private Map<String, Object> selectDocument(Connection connection, String table,
            String keyColumn, String key) throws java.sql.SQLException {
        try (java.sql.PreparedStatement select = connection.prepareStatement(
                "select * from " + table + " where " + keyColumn + " = ?")) {
            select.setObject(1, key);
            try (ResultSet resultSet = select.executeQuery()) {
                List<Map<String, Object>> rows = SuiteContext.readRows(resultSet);
                return rows.isEmpty() ? null : rows.get(0);
            }
        }
    }

    /**
     * The statement layer the executor's statements run through, on the suite's connection —
     * an explicit timeoutSeconds(0): the suite runner has no configured bound
     * (docs/contract-sql-execution.md slice 2).
     */
    private static io.tesseraql.core.sql.SqlStatement suiteStatements() {
        return io.tesseraql.core.sql.SqlStatement.onCallerConnections().timeoutSeconds(0);
    }

    private Path workflowDir(io.tesseraql.yaml.model.WorkflowDefinition def) {
        return context.manifest().workflows().stream()
                .filter(w -> def.id().equals(w.definition().id()))
                .findFirst()
                .orElseThrow()
                .source()
                .getParent();
    }
}
