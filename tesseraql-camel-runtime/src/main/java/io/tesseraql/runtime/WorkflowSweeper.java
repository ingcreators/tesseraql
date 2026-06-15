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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Escalates overdue approval-workflow tasks (roadmap Phase 28 slice 3). For each open task whose
 * deadline has passed, the matching state's {@code onBreach.reassign} contract resolves a fallback
 * assignee and the task is reassigned (its deadline cleared, so it escalates exactly once); a
 * history row records the escalation. The sweep runs in one transaction.
 *
 * <p>The cluster-safe firing — only one node sweeps per interval — is the {@link WorkflowSweepRoutes}
 * timer's job (the same {@code tql_job_claim} mechanism scheduled jobs use); this class is the work.
 */
final class WorkflowSweeper {

    /** TQL-WORKFLOW-3223: the workflow sweeper could not complete a JDBC operation. */
    private static final TqlErrorCode SWEEP_ERROR = new TqlErrorCode(TqlDomain.WORKFLOW, 3223);
    private static final int BATCH = 100;

    /**
     * A deadline's escalation: the reassign resolver bound to a document type and state, plus the
     * optional escalation reminder (roadmap Phase 28 slice 3, Phase 20 channels).
     */
    record Rule(String docType, String state, List<SqlNode> reassignNodes,
            CompiledNotify escalateNotify) {
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

    /** Escalates every overdue task with a matching reassign rule; returns the number escalated. */
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
                    String newAssignee = resolveAssignee(connection, rule, task);
                    if (newAssignee == null) {
                        continue;
                    }
                    taskStore.escalate(connection, task.taskId(), newAssignee);
                    if (workflowStore != null) {
                        workflowStore.appendHistory(connection, new WorkflowStore.History(null,
                                task.docType(), task.docId(), "escalate", task.state(),
                                task.state(), "system", Instant.now(),
                                "deadline breached; reassigned to " + newAssignee));
                    }
                    enqueueEscalateReminder(connection, rule, task, newAssignee);
                    escalated++;
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
