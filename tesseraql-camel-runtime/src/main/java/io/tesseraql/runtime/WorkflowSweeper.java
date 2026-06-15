package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.outbox.OutboxStore;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.workflow.WorkflowStore;
import io.tesseraql.core.workflow.WorkflowTaskStore;
import io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Either way a history row records the breach, and the sweep runs in one transaction.
 *
 * <p>The cluster-safe firing — only one node sweeps per interval — is the {@link WorkflowSweepRoutes}
 * timer's job (the same {@code tql_job_claim} mechanism scheduled jobs use); this class is the work.
 */
final class WorkflowSweeper {

    /** TQL-WORKFLOW-3223: the workflow sweeper could not complete a JDBC operation. */
    private static final TqlErrorCode SWEEP_ERROR = new TqlErrorCode(TqlDomain.WORKFLOW, 3223);
    private static final String SYSTEM_ACTOR = "system";
    private static final int BATCH = 100;

    /**
     * A deadline's breach handling: exactly one of {@code reassignNodes} (the reassign resolver) or
     * {@code escalate} (the auto-fired transition) is set, plus the optional escalation reminder
     * (roadmap Phase 28 slice 3, Phase 20 channels).
     */
    record Rule(String docType, String state, List<SqlNode> reassignNodes, Escalate escalate,
            CompiledNotify escalateNotify) {
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
    private final DataSource dataSource;

    WorkflowSweeper(List<Rule> rules, WorkflowTaskStore taskStore, WorkflowStore workflowStore,
            OutboxStore outboxStore, String appName, DataSource dataSource) {
        this.rules = List.copyOf(rules);
        this.taskStore = taskStore;
        this.workflowStore = workflowStore;
        this.outboxStore = outboxStore;
        this.appName = appName;
        this.dataSource = dataSource;
    }

    /** Applies each overdue task's breach handling; returns the number escalated. */
    int sweep() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int escalated = 0;
                for (WorkflowTaskStore.Overdue task : taskStore.overdue(connection, Instant.now(),
                        BATCH)) {
                    Rule rule = ruleFor(task.docType(), task.state());
                    if (rule == null) {
                        continue;
                    }
                    boolean applied = rule.escalate() != null
                            ? applyEscalate(connection, rule, task)
                            : applyReassign(connection, rule, task);
                    if (applied) {
                        escalated++;
                    }
                }
                connection.commit();
                return escalated;
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                throw ex instanceof TqlException tql ? tql : error(ex);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException ex) {
            throw error(ex);
        }
    }

    /** Reassigns the overdue task to its fallback resolver (deadline cleared, so once). */
    private boolean applyReassign(Connection connection, Rule rule, WorkflowTaskStore.Overdue task)
            throws SQLException {
        String newAssignee = resolveAssignee(connection, rule, task);
        if (newAssignee == null) {
            return false;
        }
        taskStore.escalate(connection, task.taskId(), newAssignee);
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
    private static int advanceColumn(Connection connection, Escalate escalate,
            WorkflowTaskStore.Overdue task) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("update " + escalate.table()
                + " set " + escalate.stateColumn() + " = ? where " + escalate.keyColumn()
                + " = ? and " + escalate.stateColumn() + " = ?")) {
            ps.setString(1, escalate.toState());
            ps.setString(2, task.docId());
            ps.setString(3, task.state());
            return ps.executeUpdate();
        }
    }

    /** Runs the escalation transition's command with the document key and system audit binds. */
    private static void runEscalateCommand(Connection connection, Escalate escalate,
            WorkflowTaskStore.Overdue task) throws SQLException {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("user", SYSTEM_ACTOR);
        audit.put("now", Timestamp.from(Instant.now()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", task.docId());
        params.put("audit", audit);
        BoundSql bound = SqlRenderer.render(escalate.commandNodes(), params);
        try (PreparedStatement ps = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                ps.setObject(i + 1, bound.parameters().get(i).value());
            }
            ps.executeUpdate();
        }
    }

    /**
     * Enqueues the escalation reminder on the sweep transaction's outbox (roadmap Phase 28 slice 3,
     * Phase 20 channels): the new assignee, the document, and the state are in its payload scope.
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
        if (rule.escalateNotify().fires(reminderContext)) {
            outboxStore.insert(connection, rule.escalateNotify().build(reminderContext, appName));
        }
    }

    private Rule ruleFor(String docType, String state) {
        for (Rule rule : rules) {
            if (rule.docType().equals(docType) && rule.state().equals(state)) {
                return rule;
            }
        }
        return null;
    }

    private static String resolveAssignee(Connection connection, Rule rule,
            WorkflowTaskStore.Overdue task) throws SQLException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("docId", task.docId());
        params.put("state", task.state());
        BoundSql bound = SqlRenderer.render(rule.reassignNodes(), params);
        try (PreparedStatement ps = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                ps.setObject(i + 1, bound.parameters().get(i).value());
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static TqlException error(Exception ex) {
        return TqlException.builder(SWEEP_ERROR)
                .message("Workflow sweep failed: " + ex.getMessage())
                .cause(ex).build();
    }
}
