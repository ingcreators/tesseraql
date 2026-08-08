package io.tesseraql.yaml.workflow;

import io.tesseraql.core.decision.DecisionTables;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.workflow.WorkflowStore;
import io.tesseraql.core.workflow.WorkflowTaskStore;
import io.tesseraql.yaml.decision.DecisionSets;
import io.tesseraql.yaml.model.TransitionSpec;
import io.tesseraql.yaml.model.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The transition pipeline as an engine object (docs/transition-engine.md track A): the
 * one implementation of the documented order — document load, {@code decide:}, state
 * legality, guard (both forms), task authority, conditional advance, {@code stamp:},
 * and the zero-row command contract — callable from any context holding a JDBC
 * connection. The synthesized transition routes
 * ({@code TransactionalCommandProcessor}) and the declarative suites'
 * {@code transition:} target both delegate here, so transition semantics change in
 * exactly one place.
 *
 * <p>The executor deliberately ends at "the state advanced, the stamps applied, the
 * command wrote rows": assign resolution, task opening, history, and notify stay with
 * the caller — they consume runtime-flavored collaborators the engine core does not
 * know, and the declarative suites document them as out of scope.
 *
 * <p>Refusals are typed: every step failure throws a {@link TqlException} carrying the
 * documented {@code TQL-WORKFLOW-32xx} code (and, for SQL guard files, the declared
 * refusal code under the {@code guard} detail key), so an in-process caller — the
 * dispatch selector, the suite runner — reads the refusal as data, never as a rendered
 * HTTP body.
 */
public final class TransitionExecutor {

    /** TQL-WORKFLOW-3201: a transition is not legal for the document's current state (HTTP 409). */
    private static final TqlErrorCode ILLEGAL_TRANSITION = new TqlErrorCode(TqlDomain.WORKFLOW,
            3201);

    /** TQL-WORKFLOW-3202: a transition guard rejected the request (HTTP 422). */
    private static final TqlErrorCode GUARD_FAILED = new TqlErrorCode(TqlDomain.WORKFLOW, 3202);

    /** TQL-WORKFLOW-3203: the caller holds no actionable task for the document (HTTP 403). */
    private static final TqlErrorCode NOT_ASSIGNED = new TqlErrorCode(TqlDomain.WORKFLOW, 3203);

    /**
     * TQL-WORKFLOW-3204: the transition's command updated no rows (HTTP 409) — the caller holds no
     * row authority over the document (a {@code /*%scope … *}{@code /} in the command's WHERE) or
     * the data state the command demands is absent. The documented row-authority contract
     * (docs/approval-workflow.md "guards and scopes").
     */
    private static final TqlErrorCode COMMAND_NO_ROWS = new TqlErrorCode(TqlDomain.WORKFLOW, 3204);

    private TransitionExecutor() {
    }

    /**
     * A transition compiled to what the engine needs at fire time: identifiers validated, the
     * guard expression and guard file parsed, the {@code decide:} block compiled. Built once —
     * by the route compiler at build time, by the suite runner per case — via
     * {@link #compile(WorkflowDefinition, TransitionSpec, boolean, String, Path)}.
     */
    public record CompiledTransition(String workflowId, String transitionId, String docType,
            String table, String keyColumn, String from, String to, String initial,
            boolean managed, Expr guard, List<SqlNode> guardNodes, String guardCode,
            String guardMessage, Map<String, Object> stamps, DecisionTables decisions,
            String dialect) {
    }

    /** Sees every rendered guard-file statement — the suites hook SQL coverage through this. */
    public interface GuardSqlObserver {
        void observe(List<SqlNode> nodes, BoundSql bound);
    }

    /**
     * The per-call collaborators the engine core does not own: the state store (managed bean or
     * app-mode column store), the task store (or {@code null} — callers that do not model task
     * authority, like the suites, skip the check), the scope resolver for guard-file rendering,
     * the caller's identity for task authority, the decision timeout, and the optional guard-SQL
     * observer.
     */
    public record Collaborators(WorkflowStore store, WorkflowTaskStore taskStore,
            ScopeResolver scopes, String actorSubject, List<String> actorGroups,
            int decisionTimeoutSeconds, GuardSqlObserver guardObserver) {
    }

    /**
     * Compiles a transition for the executor: parses the guard (expression or SQL file — a
     * missing or malformed guard file fails the build, not the first request), validates stamp
     * column identifiers (the only string that reaches the UPDATE's column position), and
     * compiles the {@code decide:} block.
     */
    public static CompiledTransition compile(WorkflowDefinition def, TransitionSpec transition,
            boolean managed, String dialect, Path workflowDir) {
        Expr guard = null;
        List<SqlNode> guardNodes = null;
        if (transition.guard() != null && transition.guard().expression() != null) {
            guard = ExpressionParser.parse(transition.guard().expression());
        } else if (transition.guard() != null && transition.guard().file() != null) {
            Path guardFile = workflowDir.resolve(transition.guard().file());
            try {
                guardNodes = Sql2WayParser.parse(Files.readString(guardFile));
            } catch (java.io.IOException unreadable) {
                throw new IllegalStateException("Workflow '" + def.id() + "' transition '"
                        + transition.id() + "': guard file '" + transition.guard().file()
                        + "' is unreadable: " + unreadable.getMessage(), unreadable);
            }
        }
        for (String column : transition.stamp().keySet()) {
            if (!column.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new IllegalStateException("Workflow '" + def.id() + "' transition '"
                        + transition.id() + "': stamp column '" + column
                        + "' is not a plain identifier");
            }
        }
        return new CompiledTransition(def.id(), transition.id(), def.document().type(),
                def.document().table(), def.document().key(), transition.from(), transition.to(),
                def.initial(), managed,
                guard, guardNodes,
                transition.guard() == null ? null : transition.guard().code(),
                transition.guard() == null ? null : transition.guard().message(),
                transition.stamp(),
                DecisionSets.compileUses(transition.decide(), dialect), dialect);
    }

    /**
     * Runs the pre-advance pipeline inside the caller's transaction: ensures the managed
     * instance, loads the document into the context as {@code document}, evaluates
     * {@code decide:} (after the document binds, before the guard — the wiring may read
     * {@code document.*}, the guard may consume {@code decision.*}), verifies state legality,
     * evaluates the guard in whichever form it declares, and checks task authority. Returns the
     * in-flight {@link Session}; every refusal throws its documented code.
     */
    public static Session begin(Connection connection, CompiledTransition transition,
            Collaborators collaborators, String docId, Map<String, Object> context)
            throws SQLException {
        Object tenant = context.get("tenant");
        String tenantId = tenant == null ? null : String.valueOf(tenant);
        collaborators.store().ensureInstance(connection, transition.docType(), docId,
                transition.initial(), tenantId);
        context.put("document", loadDocument(connection, transition.table(),
                transition.keyColumn(), transition.dialect(), docId));
        if (!transition.decisions().isEmpty()) {
            context.put(io.tesseraql.core.sql.AmbientBinds.DECISION,
                    transition.decisions().evaluate(context, connection,
                            collaborators.decisionTimeoutSeconds()));
        }
        String current = collaborators.store().currentState(connection, transition.docType(),
                docId);
        String from = current != null ? current : transition.initial();
        if (!java.util.Objects.equals(transition.from(), from)) {
            throw illegalTransition(transition, from, "the document is in state '" + from + "'");
        }
        if (transition.guard() != null
                && !transition.guard().evalBoolean(new EvaluationContext(context))) {
            throw TqlException.builder(GUARD_FAILED)
                    .message("Workflow '" + transition.workflowId() + "': transition '"
                            + transition.transitionId() + "' guard rejected the request")
                    .build();
        }
        // The SQL guard form (docs/workflow-expressiveness.md): a 2-way query evaluated on
        // the transition's connection — rows pass, no rows fails with the declared code
        // riding the payload, so the caller learns WHY, not just "Unprocessable Entity".
        if (transition.guardNodes() != null) {
            // The guard sees what the command sees: the request context plus the resolved
            // document key under `key` (a command gets it from its params wiring; the guard
            // has no wiring, so it is seeded here).
            Map<String, Object> guardParams = new LinkedHashMap<>(context);
            guardParams.putIfAbsent("key", docId);
            BoundSql bound = SqlRenderer.render(transition.guardNodes(), guardParams,
                    collaborators.scopes(), guardParams);
            if (collaborators.guardObserver() != null) {
                collaborators.guardObserver().observe(transition.guardNodes(), bound);
            }
            boolean holds;
            try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
                for (int i = 0; i < bound.parameters().size(); i++) {
                    statement.setObject(i + 1, bound.parameters().get(i).value());
                }
                try (ResultSet rows = statement.executeQuery()) {
                    holds = rows.next();
                }
            }
            if (!holds) {
                // The renderer nests details under `error.details`, so the declared refusal
                // rides the natural names: `code` is the app's refusal code, `message` its text.
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("code", transition.guardCode() == null
                        ? "guard-failed"
                        : transition.guardCode());
                if (transition.guardMessage() != null) {
                    details.put("message", transition.guardMessage());
                }
                throw TqlException.builder(GUARD_FAILED)
                        .message("Workflow '" + transition.workflowId() + "': transition '"
                                + transition.transitionId() + "' guard matched no rows")
                        .details(details)
                        .build();
            }
        }
        // Task authority (roadmap Phase 28 slice 2): a document with open tasks may only be
        // transitioned by someone who holds one (the direct assignee or a candidate group). A
        // document with no open tasks (an initial or unassigned state) is gated only by route
        // policy.
        if (collaborators.taskStore() != null) {
            List<String> groups = collaborators.actorGroups() == null
                    ? List.of()
                    : collaborators.actorGroups();
            if (collaborators.taskStore().hasOpenTasks(connection, transition.docType(), docId)
                    && !collaborators.taskStore().canAct(connection, transition.docType(), docId,
                            collaborators.actorSubject(), groups)) {
                throw TqlException.builder(NOT_ASSIGNED)
                        .message("Workflow '" + transition.workflowId() + "': transition '"
                                + transition.transitionId()
                                + "' requires an assigned task the caller does not hold")
                        .build();
            }
        }
        return new Session(transition, collaborators, docId, from, tenantId);
    }

    /**
     * An in-flight transition between the legality/guard checks and the caller's command: the
     * caller advances, runs its scoped command, and reports the affected rows for the zero-row
     * contract.
     */
    public static final class Session {

        private final CompiledTransition transition;
        private final Collaborators collaborators;
        private final String docId;
        private final String fromState;
        private final String tenant;

        private Session(CompiledTransition transition, Collaborators collaborators, String docId,
                String fromState, String tenant) {
            this.transition = transition;
            this.collaborators = collaborators;
            this.docId = docId;
            this.fromState = fromState;
            this.tenant = tenant;
        }

        public String docId() {
            return docId;
        }

        public String fromState() {
            return fromState;
        }

        public String tenant() {
            return tenant;
        }

        public WorkflowStore store() {
            return collaborators.store();
        }

        public WorkflowTaskStore taskStore() {
            return collaborators.taskStore();
        }

        /**
         * Advances the state before the command — the conditional UPDATE affects zero rows when
         * the document is no longer in {@code from} (a concurrent transition), which is a 409 —
         * then applies the transition's decision stamps (docs/workflow-expressiveness.md
         * slice 2): one engine-issued {@code UPDATE <table> SET col = ?, … WHERE <key> = ?} in
         * the same transaction, after the advance and before the author command. The in-memory
         * document map refreshes so anything later in the transaction reading
         * {@code document.<column>} sees the stamped value.
         */
        public void advance(Connection connection, Map<String, Object> context)
                throws SQLException {
            int advanced = collaborators.store().advanceState(connection, transition.docType(),
                    docId, transition.from(), transition.to());
            if (advanced == 0) {
                throw illegalTransition(transition, fromState,
                        "the document changed state concurrently");
            }
            applyStamps(connection, context);
        }

        /**
         * The documented row-authority contract (docs/approval-workflow.md "guards and
         * scopes"): a transition whose command ran but updated nothing — a {@code /*%scope *}
         * {@code /} that matched no rows, or an absent data state the WHERE demands — must not
         * advance the state; the caller enforces it here before history/tasks commit.
         */
        public void enforceCommandRows(boolean anyExecuted, int totalAffected) {
            if (anyExecuted && totalAffected == 0) {
                throw TqlException.builder(COMMAND_NO_ROWS)
                        .message("Transition '" + transition.transitionId()
                                + "' updated no rows — outside the caller's row"
                                + " authority or the required data state is absent")
                        .build();
            }
        }

        private void applyStamps(Connection connection, Map<String, Object> context)
                throws SQLException {
            if (transition.stamps().isEmpty()) {
                return;
            }
            EvaluationContext evaluation = new EvaluationContext(context);
            Map<String, Object> resolved = new LinkedHashMap<>();
            transition.stamps().forEach((column, value) -> resolved.put(column,
                    resolveStamp(evaluation, value)));
            StringBuilder sql = new StringBuilder("update ").append(transition.table())
                    .append(" set ");
            sql.append(String.join(", ",
                    resolved.keySet().stream().map(column -> column + " = ?").toList()));
            sql.append(" where ").append(transition.keyColumn()).append(" = ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (Object value : resolved.values()) {
                    statement.setObject(index++, value);
                }
                statement.setObject(index, docId);
                statement.executeUpdate();
            }
            if (context.get("document") instanceof Map<?, ?> document) {
                Map<String, Object> refreshed = new LinkedHashMap<>();
                document.forEach((k, v) -> refreshed.put(String.valueOf(k), v));
                refreshed.putAll(resolved);
                context.put("document", refreshed);
            }
        }
    }

    /** A string rooted at {@code decision.}/{@code document.}/{@code principal.} resolves as a path; anything else — including {@code null}, a rework's declared clearing — is the literal. */
    private static Object resolveStamp(EvaluationContext evaluation, Object value) {
        if (value instanceof String path && (path.startsWith("decision.")
                || path.startsWith("document.") || path.startsWith("principal."))) {
            return evaluation.resolve(Arrays.asList(path.split("\\.")));
        }
        return value;
    }

    /**
     * Loads the document row by key, shaping labels and values the way every other read does, so
     * a guard reads {@code document.col} with the same spelling a response binding would. Public
     * because the dispatch selector loads the document once for its dispatch-level
     * {@code decide:} (docs/transition-engine.md track B).
     */
    public static Map<String, Object> loadDocument(Connection connection, String table,
            String keyColumn, String dialect, String docId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("select * from "
                + table + " where " + keyColumn + " = ?")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of();
                }
                java.sql.ResultSetMetaData metaData = rs.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int col = 1; col <= metaData.getColumnCount(); col++) {
                    row.put(io.tesseraql.core.dialect.ResultRows.label(dialect,
                            metaData.getColumnLabel(col)),
                            io.tesseraql.core.dialect.ResultRows.value(rs.getObject(col)));
                }
                return row;
            }
        }
    }

    private static TqlException illegalTransition(CompiledTransition transition,
            String actualState, String reason) {
        return TqlException.builder(ILLEGAL_TRANSITION)
                .message("Workflow '" + transition.workflowId() + "': transition '"
                        + transition.transitionId() + "' requires state '" + transition.from()
                        + "' but "
                        + reason)
                .details(Map.of("conflict", Map.of(
                        "expectedState", String.valueOf(transition.from()),
                        "actualState", String.valueOf(actualState),
                        "hint", "tql.workflow.illegal-transition")))
                .build();
    }
}
