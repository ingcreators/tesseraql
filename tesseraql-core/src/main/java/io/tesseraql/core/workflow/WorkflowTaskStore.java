package io.tesseraql.core.workflow;

import java.sql.Connection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The task inbox behind the approval-workflow state machine (roadmap Phase 28, slices 2–3): the open
 * and completed tasks a transition produces, the authority check confining who may act, and the
 * deadlines the sweeper escalates.
 *
 * <p>Assignee resolution is the dual of a data scope: a transition's {@code assign} contract maps a
 * document to the principals (or candidate groups) who receive the resulting task — the same
 * org-unit graph data scoping reads, the opposite direction. Tasks are always kept in the managed
 * {@code tql_workflow_task} table, independent of where a workflow keeps its state (managed instance
 * row or app column), so one inbox spans every workflow.
 *
 * <p>Every write takes the caller's {@link Connection} so task creation and completion commit
 * atomically with the transition's state change and business writes.
 */
public interface WorkflowTaskStore {

    /** Opens a task within the caller's transaction. */
    void openTask(Connection cx, Task task);

    /**
     * Completes every open task for the document, recording {@code actor} as the one who acted.
     * Called when a document leaves a state, before the new state's tasks are opened.
     */
    void completeOpenTasks(Connection cx, String docType, String docId, String actor);

    /** Whether the document has any open task at all (a state that gates who may act). */
    boolean hasOpenTasks(Connection cx, String docType, String docId);

    /**
     * Whether the principal may act on the document: it holds an open task as the direct assignee or
     * through one of its candidate groups. Combined with {@link #hasOpenTasks} this is the authority
     * check — a document with open tasks may only be transitioned by someone who holds one.
     */
    boolean canAct(Connection cx, String docType, String docId, String subject,
            Collection<String> groups);

    /**
     * Reassigns the document's open tasks to a new direct assignee, keeping their deadlines (roadmap
     * Phase 28 slice 3). Delegation uses this so the delegate sees the task in their inbox.
     */
    void reassignOpenTasks(Connection cx, String docType, String docId, String newAssignee,
            String delegatedFrom);

    /**
     * Escalates an overdue task to a new assignee and clears its deadline, so the cluster-safe
     * sweeper acts on it exactly once (roadmap Phase 28 slice 3).
     */
    void escalate(Connection cx, String taskId, String newAssignee, String delegatedFrom);

    /**
     * The open tasks whose deadline has passed as of {@code asOf} (up to {@code limit}), the
     * sweeper's work-list (roadmap Phase 28 slice 3).
     */
    List<Overdue> overdue(Connection cx, Instant asOf, int limit);

    /**
     * One open task: the document, the state it is open in, and who can act — a direct
     * {@code assignee}, a {@code candidateGroup}, or both (at least one is non-null).
     *
     * @param docType        the workflow {@code document.type}
     * @param docId          the business document key
     * @param state          the state the task is open in
     * @param assignee       the direct assignee principal subject, or {@code null}
     * @param candidateGroup the candidate group whose members may claim it, or {@code null}
     * @param dueAt          when the task breaches its deadline, or {@code null} for no deadline
     * @param tenantId       the owning tenant, or {@code null} when tenancy is not used
     */
    record Task(String docType, String docId, String state, String assignee, String candidateGroup,
            Instant dueAt, String tenantId, String delegatedFrom) {

        /** The pre-Phase-52 shape (no absence redirect) for positional callers. */
        public Task(String docType, String docId, String state, String assignee,
                String candidateGroup, Instant dueAt, String tenantId) {
            this(docType, docId, state, assignee, candidateGroup, dueAt, tenantId, null);
        }
    }

    /**
     * An overdue open task the sweeper must escalate.
     *
     * @param taskId   the task id
     * @param docType  the workflow {@code document.type}
     * @param docId    the business document key
     * @param state    the state the task is open in
     * @param assignee the current assignee, or {@code null}
     */
    record Overdue(String taskId, String docType, String docId, String state, String assignee) {
    }
}
