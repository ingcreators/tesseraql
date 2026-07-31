package io.tesseraql.compiler.binding;

import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.workflow.WorkflowStore;
import io.tesseraql.yaml.workflow.TransitionExecutor;
import java.util.List;
import java.util.Map;

/**
 * The workflow context a synthesized transition route carries into the
 * {@link TransactionalCommandProcessor} (roadmap Phase 28 slice 1). The transition pipeline
 * itself — document load, decide, legality, guard, task authority, advance, stamps, the
 * zero-row contract — lives in the {@link TransitionExecutor}
 * (docs/transition-engine.md); this binding carries the compiled transition plus the
 * route-flavored collaborators the executor deliberately does not own: assign resolution,
 * task deadlines, and the assignment reminder.
 *
 * @param transition   the compiled transition the executor fires
 * @param keyExpr      the context path yielding the document key, e.g. {@code path.key}
 * @param appStore     the app-mode store (a {@code ColumnWorkflowStore}), or {@code null} when
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
public record WorkflowBinding(TransitionExecutor.CompiledTransition transition, String keyExpr,
        WorkflowStore appStore, List<SqlNode> assignNodes,
        Map<String, String> assignParams, Long dueWithinMillis,
        io.tesseraql.yaml.notify.NotifyEvents.CompiledNotify assignNotify) {
}
