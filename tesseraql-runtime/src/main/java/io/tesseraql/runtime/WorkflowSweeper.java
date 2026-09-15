package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.outbox.OutboxStore;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.workflow.WorkflowStore;
import io.tesseraql.core.workflow.WorkflowTaskStore;
import io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Escalates overdue approval-workflow tasks (roadmap Phase 28 slice 3). For each open task whose
 * deadline has passed, the matching state's {@code onBreach} applies — either:
 *
 * <ul>
 *   <li>{@code reassign}: the resolver picks a fallback assignee, the task is reassigned and its
 *       deadline cleared (so it escalates exactly once); or</li>
 *   <li>{@code escalate}: the named transition is auto-fired as the system — the state advances, the
 *       transition's command runs (with {@code /* key *}{@code /} and {@code /* audit.* *}{@code /}
 *       binds), the open tasks complete, and a history row records the auto-escalation.</li>
 * </ul>
 *
 * Either way a history row records the breach. The sweep runs in one transaction, and each task's
 * breach handling behind its own savepoint (docs/audit-low-leads.md slice 2b): one task whose
 * resolver or command fails is rolled back to its savepoint, named at WARNING, and skipped, while
 * the others in the batch still commit — before this, the first failing task rolled the whole
 * batch back and, being the earliest overdue, met the sweeper first on every sweep after.
 *
 * <p>The sweeper acts <em>as the system</em>: the escalated command renders its
 * {@code /*%scope … *}{@code /} directives through the system resolver (every declared scope is
 * {@code (1=1)} — the deadline the author declared is the write authority, there is no requester
 * whose org unit could confine it), and the reassign resolver is bound the way the route binds
 * {@code assign:} — {@code key}, {@code audit.*}, the declared {@code params:} against the loaded
 * document — so one assignee-resolution contract has one bind set.
 *
 * <p>The cluster-safe firing — only one node sweeps per interval — is the {@link WorkflowSweep}
 * schedule's job (the same {@code tql_job_claim} mechanism scheduled jobs use); this class is the work.
 */
final class WorkflowSweeper {

    private static final System.Logger LOG = System.getLogger(WorkflowSweeper.class.getName());

    /** TQL-WORKFLOW-3223: the workflow sweeper could not complete a JDBC operation. */
    private static final TqlErrorCode SWEEP_ERROR = new TqlErrorCode(TqlDomain.WORKFLOW, 3223);
    /**
     * TQL-WORKFLOW-3204: the escalated transition's command matched no row — the same refusal the
     * route gives the same command, so the managed state never runs ahead of the business row.
     */
    private static final TqlErrorCode COMMAND_NO_ROWS = new TqlErrorCode(TqlDomain.WORKFLOW, 3204);
    private static final String SYSTEM_ACTOR = "system";
    private static final int BATCH = 100;

    /**
     * A deadline's breach handling: exactly one of {@code reassign} (the reassign resolver) or
     * {@code escalate} (the auto-fired transition) is set, plus the optional escalation reminder
     * (roadmap Phase 28 slice 3, Phase 20 channels). {@code document} is the workflow's document
     * declaration, loaded for the reassign resolver's declared {@code params:}.
     */
    record Rule(String docType, String state, Reassign reassign, Escalate escalate,
            CompiledNotify escalateNotify, Document document) {
    }

    /**
     * The {@code onBreach.reassign} resolver: the parsed SQL and its declared {@code params:}
     * (bind name to a dotted path over the sweep context — {@code document.*}, {@code key},
     * {@code docId}, {@code state}, {@code audit.*}).
     */
    record Reassign(List<SqlNode> nodes, Map<String, String> params) {
        Reassign {
            params = params == null ? Map.of() : io.tesseraql.core.util.OrderedCopies.map(params);
        }
    }

    /** The workflow's document declaration, as the resolver needs it to load the row. */
    record Document(String table, String keyColumn, String dialect) {
    }

    /**
     * The {@code onBreach.escalate} transition the sweeper auto-fires as the system: it advances the
     * document from the deadline's state to {@code toState} and runs the optional {@code command}.
     */
    record Escalate(String transitionId, String toState, List<SqlNode> commandNodes,
            boolean managed,
            String table, String stateColumn, String keyColumn) {
    }

    private final List<Rule> rules;
    private final WorkflowTaskStore taskStore;
    private final WorkflowStore workflowStore;
    private final OutboxStore outboxStore;
    private final String appName;
    /** Absence resolution for reassign fallbacks (roadmap Phase 52); nullable. */
    private final io.tesseraql.core.workflow.DelegationStore delegations;
    private io.tesseraql.core.sql.SqlStatement statements;
    /** The per-user opt-out the escalation reminder consults (roadmap Phase 48); nullable. */
    private io.tesseraql.core.account.PreferenceStore preferences;
    /** The system's scope resolver; the reject-any-scope default until the runtime wires one. */
    private ScopeResolver scopeResolver = ScopeResolver.UNSUPPORTED;

    WorkflowSweeper(List<Rule> rules, WorkflowTaskStore taskStore, WorkflowStore workflowStore,
            OutboxStore outboxStore, String appName, DataSource dataSource,
            io.tesseraql.core.workflow.DelegationStore delegations) {
        this.rules = List.copyOf(rules);
        this.taskStore = taskStore;
        this.workflowStore = workflowStore;
        this.outboxStore = outboxStore;
        this.appName = appName;
        this.delegations = delegations;
        this.statements = io.tesseraql.core.sql.SqlStatement.on(dataSource)
                .surface("workflow");
    }

    /**
     * The bound every sweep statement runs under, in seconds; {@code 0} leaves it unset
     * (docs/contract-sql-execution.md slice 2).
     *
     * <p>There was none: an application's escalate command and reassign resolver ran for as long
     * as the driver allowed, inside the sweep's transaction, where the same statement on a route
     * has been bounded by {@code tesseraql.sql.timeoutSeconds} all along.
     */
    WorkflowSweeper sqlTimeoutSeconds(int seconds) {
        this.statements = statements.timeoutSeconds(seconds);
        return this;
    }

    /** The tracer each sweep statement spans through (docs/contract-sql-execution.md slice 7). */
    WorkflowSweeper tracer(io.tesseraql.core.telemetry.Tracer tracer) {
        this.statements = statements.tracer(tracer);
        return this;
    }

    /**
     * The preference store the escalation reminder's opt-out is read from; absent, no one has
     * opted out (the account surface is what stores the preference in the first place).
     */
    WorkflowSweeper preferences(io.tesseraql.core.account.PreferenceStore preferences) {
        this.preferences = preferences;
        return this;
    }

    /**
     * The resolver the sweeper renders {@code /*%scope … *}{@code /} directives through — the
     * system's, where every declared scope is {@code (1=1)} (docs/data-scoping.md). Left at the
     * reject-any-scope default, a scoped command threw TQL-SQL-2106 on every sweep for the life
     * of the deployment (docs/audit-low-leads.md G33).
     */
    WorkflowSweeper scopeResolver(ScopeResolver scopeResolver) {
        this.scopeResolver = scopeResolver == null ? ScopeResolver.UNSUPPORTED : scopeResolver;
        return this;
    }

    /**
     * Applies each overdue task's breach handling; returns the number escalated. The
     * open-run-commit-restore bracket is {@link io.tesseraql.core.sql.SqlStatement#transact},
     * not a hand-rolled copy: the copy this replaced neither suppressed a failing rollback
     * into the failure that mattered nor kept a failing autocommit restore from masking a
     * committed sweep as an error.
     *
     * <p>Each task runs behind a savepoint (the jobs platform's per-row pattern): a failing
     * task is rolled back to it and skipped with a WARNING that names it, so it cannot poison
     * the batch. It is met again next sweep — a persistent failure repeats the WARNING once per
     * interval, which is the signal an operator needs, and the other tasks keep moving.
     */
    int sweep() {
        try {
            return statements.transact("workflow.sweep", connection -> {
                int escalated = 0;
                for (WorkflowTaskStore.Overdue task : taskStore.overdue(connection, Instant.now(),
                        BATCH)) {
                    Rule rule = ruleFor(task.docType(), task.state());
                    if (rule == null) {
                        continue;
                    }
                    Savepoint beforeTask = connection.setSavepoint();
                    try {
                        boolean applied = rule.escalate() != null
                                ? applyEscalate(connection, rule, task)
                                : applyReassign(connection, rule, task);
                        if (applied) {
                            escalated++;
                        }
                    } catch (SQLException | RuntimeException failure) {
                        // The failed statement may have poisoned the transaction (PostgreSQL
                        // aborts it) — the savepoint keeps the batch.
                        connection.rollback(beforeTask);
                        LOG.log(System.Logger.Level.WARNING,
                                "Workflow task {0} ({1}/{2}, state {3}) breached its deadline"
                                        + " but its onBreach failed and was skipped this sweep: {4}",
                                task.taskId(), task.docType(), task.docId(), task.state(),
                                failure.getMessage());
                    }
                }
                return escalated;
            });
        } catch (io.tesseraql.core.sql.SqlStatementException ex) {
            throw error(ex);
        }
    }

    /**
     * Reassigns the overdue task to its fallback resolver (deadline cleared, so once). A resolver
     * that answers no row is loud and still once: the task keeps its assignee, its deadline is
     * cleared, the history says so, and the WARNING names it — before this it was re-evaluated
     * silently on every sweep for ever (docs/audit-low-leads.md G36).
     */
    private boolean applyReassign(Connection connection, Rule rule, WorkflowTaskStore.Overdue task)
            throws SQLException {
        String newAssignee = resolveAssignee(connection, rule, task);
        if (newAssignee == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "Workflow task {0} ({1}/{2}, state {3}) breached its deadline but the reassign"
                            + " resolver answered no assignee; the task stays with {4} and the"
                            + " breach is recorded as handled",
                    task.taskId(), task.docType(), task.docId(), task.state(), task.assignee());
            taskStore.clearDeadline(connection, task.taskId());
            if (workflowStore != null) {
                workflowStore.appendHistory(connection, new WorkflowStore.History(null,
                        task.docType(), task.docId(), "escalate", task.state(), task.state(),
                        SYSTEM_ACTOR, Instant.now(),
                        "deadline breached; reassign resolver answered no assignee, task stays with "
                                + task.assignee()));
            }
            return false;
        }
        // Absence resolution for the fallback too (roadmap Phase 52), under the tenant the task
        // was opened in — the rule an approver wrote lives under their tenant (docs/delegation.md).
        io.tesseraql.core.workflow.Delegations.Resolved resolved = io.tesseraql.core.workflow.Delegations
                .resolve(delegations, task.tenantId(), newAssignee);
        newAssignee = resolved.assignee();
        taskStore.escalate(connection, task.taskId(), newAssignee, resolved.delegatedFrom());
        if (workflowStore != null) {
            workflowStore.appendHistory(connection, new WorkflowStore.History(null, task.docType(),
                    task.docId(), "escalate", task.state(), task.state(), SYSTEM_ACTOR,
                    Instant.now(), "deadline breached; reassigned to " + newAssignee));
        }
        enqueueEscalateReminder(connection, rule, task, newAssignee);
        return true;
    }

    /** Auto-fires the {@code onBreach.escalate} transition as the system. */
    private boolean applyEscalate(Connection connection, Rule rule, WorkflowTaskStore.Overdue task)
            throws SQLException {
        Escalate escalate = rule.escalate();
        int advanced = escalate.managed()
                ? (workflowStore == null
                        ? 0
                        : workflowStore.advanceState(connection,
                                task.docType(), task.docId(), task.state(), escalate.toState()))
                : advanceColumn(connection, escalate, task);
        if (advanced == 0) {
            // The document left the deadline's state concurrently; nothing to escalate.
            return false;
        }
        if (escalate.commandNodes() != null) {
            runEscalateCommand(connection, escalate, task);
        }
        taskStore.completeOpenTasks(connection, task.docType(), task.docId(), SYSTEM_ACTOR);
        if (workflowStore != null) {
            workflowStore.appendHistory(connection, new WorkflowStore.History(null, task.docType(),
                    task.docId(), escalate.transitionId(), task.state(), escalate.toState(),
                    SYSTEM_ACTOR, Instant.now(),
                    "deadline breached; auto-escalated via " + escalate.transitionId()));
        }
        return true;
    }

    /** App-mode state advance: a conditional UPDATE of the business table's state column. */
    private int advanceColumn(Connection connection, Escalate escalate,
            WorkflowTaskStore.Overdue task) throws SQLException {
        return statements.update(connection, "workflow.sweep.advance",
                "update " + escalate.table() + " set " + escalate.stateColumn()
                        + " = ? where " + escalate.keyColumn() + " = ? and "
                        + escalate.stateColumn() + " = ?",
                List.of(escalate.toState(), task.docId(), task.state()));
    }

    /**
     * Runs the escalation transition's command with the document key and system audit binds,
     * its scope directives rendered as the system. A command that matched no row is the
     * route's refusal (TQL-WORKFLOW-3204) here too: the task's savepoint takes the state
     * advance back with it, so the history never asserts a change the table does not show.
     */
    private void runEscalateCommand(Connection connection, Escalate escalate,
            WorkflowTaskStore.Overdue task) throws SQLException {
        Map<String, Object> params = systemBinds(task);
        BoundSql bound = SqlRenderer.render(escalate.commandNodes(), params, scopeResolver,
                params);
        int affected = statements.update(connection, "workflow.sweep.escalate", bound);
        if (affected == 0) {
            throw TqlException.builder(COMMAND_NO_ROWS)
                    .message("Escalation '" + escalate.transitionId() + "' of " + task.docType()
                            + "/" + task.docId() + " updated no rows — the required data state"
                            + " is absent; the document stays in '" + task.state() + "'")
                    .build();
        }
    }

    /**
     * Enqueues the escalation reminder on the sweep transaction's outbox (roadmap Phase 28 slice 3,
     * Phase 20 channels): the new assignee, the document, and the state are in its payload scope.
     *
     * <p>Addressed the way a route's {@code notify:} is: the declared {@code recipient:} resolved
     * against that scope and the task's own tenant ride the envelope, and the recipient's
     * per-channel opt-out is honoured at enqueue. The sweeper has no request principal, so the
     * tenant is the one the task was opened under — which is why {@link WorkflowTaskStore.Overdue}
     * carries it. Before this the reminder used the recipient-less {@code build}, so an inbox
     * reminder dead-lettered every time (docs/audit-low-leads.md G34).
     */
    private void enqueueEscalateReminder(Connection connection, Rule rule,
            WorkflowTaskStore.Overdue task, String newAssignee) {
        if (rule.escalateNotify() == null || outboxStore == null) {
            return;
        }
        Map<String, Object> reminderContext = new LinkedHashMap<>();
        reminderContext.put("assignee", newAssignee);
        reminderContext.put("docType", task.docType());
        reminderContext.put("docId", task.docId());
        reminderContext.put("state", task.state());
        if (!rule.escalateNotify().fires(reminderContext)
                || io.tesseraql.yaml.notify.NotifyOptOut.optedOut(rule.escalateNotify(),
                        reminderContext, preferences, task.tenantId())) {
            return;
        }
        outboxStore.insert(connection, rule.escalateNotify().build(reminderContext, appName,
                rule.escalateNotify().resolveRecipient(reminderContext), task.tenantId()));
    }

    private Rule ruleFor(String docType, String state) {
        for (Rule rule : rules) {
            if (rule.docType().equals(docType) && rule.state().equals(state)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Resolves the fallback assignee, binding the resolver the way the route binds
     * {@code assign:}: {@code key} (the document key; {@code docId} stays as its alias),
     * {@code state}, {@code audit.*} as the system, and the declared {@code params:} resolved
     * against the sweep context — the loaded {@code document} row plus those same names. Before
     * this the resolver saw {@code docId} and {@code state} only, and the {@code params:} the
     * YAML accepted were dropped at boot (docs/audit-low-leads.md G36).
     */
    private String resolveAssignee(Connection connection, Rule rule,
            WorkflowTaskStore.Overdue task) throws SQLException {
        Map<String, Object> params = systemBinds(task);
        if (!rule.reassign().params().isEmpty()) {
            Map<String, Object> context = new LinkedHashMap<>(params);
            context.put("document", io.tesseraql.yaml.workflow.TransitionExecutor.loadDocument(
                    connection, rule.document().table(), rule.document().keyColumn(),
                    rule.document().dialect(), task.docId(), statements));
            EvaluationContext evaluation = new EvaluationContext(context);
            rule.reassign().params().forEach((bindName, sourceExpr) -> params.put(bindName,
                    evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        }
        BoundSql bound = SqlRenderer.render(rule.reassign().nodes(), params, scopeResolver,
                params);
        return statements.read(connection, "workflow.sweep.reassign", bound,
                rs -> rs.next() ? rs.getString(1) : null);
    }

    /** The binds every sweep-fired statement sees: the document key and the system's audit. */
    private static Map<String, Object> systemBinds(WorkflowTaskStore.Overdue task) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("user", SYSTEM_ACTOR);
        audit.put("now", Timestamp.from(Instant.now()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", task.docId());
        params.put("docId", task.docId());
        params.put("state", task.state());
        params.put("audit", audit);
        return params;
    }

    private static TqlException error(Exception ex) {
        return TqlException.builder(SWEEP_ERROR)
                .message("Workflow sweep failed: " + ex.getMessage())
                .cause(ex).build();
    }
}
