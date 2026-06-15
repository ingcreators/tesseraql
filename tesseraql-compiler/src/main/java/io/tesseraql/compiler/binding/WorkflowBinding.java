package io.tesseraql.compiler.binding;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.workflow.WorkflowStore;
import java.util.List;
import java.util.Map;

/**
 * The workflow context a synthesized transition route carries into the
 * {@link TransactionalCommandProcessor} (roadmap Phase 28 slice 1). When present, the processor —
 * inside the command's transaction — loads the document, checks the transition is legal for the
 * current state, evaluates the guard, advances the state, and appends a history row, around the
 * author's command.
 *
 * @param workflowId   the owning workflow id (for messages and history)
 * @param transitionId the transition id (for messages and history)
 * @param docType      the workflow {@code document.type} (managed instance addressing)
 * @param table        the business table the document lives in (for the guard's document load)
 * @param keyColumn    the SQL column holding the document key
 * @param keyExpr      the context path yielding the key value, e.g. {@code path.key}
 * @param from         the state the document must be in
 * @param to           the state the transition moves it to
 * @param initial      the workflow's initial state (the current state of a never-transitioned doc)
 * @param managed      whether state lives in the managed {@code tql_workflow_instance} table
 * @param guard        the parsed guard expression, or {@code null} for an unconditional transition
 * @param appStore     the app-mode store (a {@link ColumnWorkflowStore}), or {@code null} when
 *                     managed (the runtime-bound {@link WorkflowStore} bean is used instead)
 * @param assignNodes  the parsed assignee-resolution SQL (a {@code SELECT} returning {@code assignee}
 *                     / {@code candidate_group} rows), or {@code null} when the transition assigns no
 *                     task (roadmap Phase 28 slice 2)
 * @param assignParams the assignee-resolution binds, resolved against the request context per call
 * @param dueWithinMillis the {@code to} state's deadline in milliseconds, set as the opened task's
 *                     {@code due_at}, or {@code null} when the state has no deadline (Phase 28
 *                     slice 3)
 * @param assignNotify the reminder fired when a task is opened (Phase 20 channels), or {@code null};
 *                     the resolved {@code assignee} is in its payload scope
 */
public record WorkflowBinding(String workflowId, String transitionId, String docType, String table,
        String keyColumn, String keyExpr, String from, String to, String initial, boolean managed,
        Expr guard, WorkflowStore appStore, List<SqlNode> assignNodes,
        Map<String, String> assignParams, Long dueWithinMillis,
        io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify assignNotify) {
}
