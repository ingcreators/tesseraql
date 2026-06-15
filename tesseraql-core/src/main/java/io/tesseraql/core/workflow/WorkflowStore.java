package io.tesseraql.core.workflow;

import java.sql.Connection;
import java.time.Instant;

/**
 * The persistence seam behind the approval-workflow state machine (roadmap Phase 28, slice 1).
 *
 * <p>Consistent with IAM's managed/SQL realm duality, two implementations back it: a managed store
 * keeps state in the framework's {@code tql_workflow_*} tables, while an app-mode store keeps state
 * in a column on the application's own business table. Every write takes a caller-supplied
 * {@link Connection} so the state advance and history append commit atomically with the transition's
 * business writes — the connection-threaded idiom {@code OutboxStore.insert(Connection, ...)} uses.
 *
 * <p>Slice 1 implements the instance-and-history subset below; task creation, assignee resolution,
 * and deadline sweeping (the {@code openTask}/{@code overdue} methods) arrive in slices 2 and 3.
 */
public interface WorkflowStore {

    /**
     * The current state of a document instance, or {@code null} when no instance exists yet (a
     * document that has never transitioned). The caller treats {@code null} as the workflow's
     * initial state.
     */
    String currentState(Connection cx, String docType, String docId);

    /**
     * Ensures an instance row exists for the document, created at {@code initial} when absent.
     * Idempotent: a second call for an existing instance is a no-op (it never resets the state). The
     * app-mode store, where state lives on the business row, implements this as a no-op.
     */
    void ensureInstance(Connection cx, String docType, String docId, String initial,
            String tenantId);

    /**
     * Conditionally advances the instance from {@code from} to {@code to}, returning the number of
     * rows affected. A return of {@code 0} means the document was no longer in {@code from} (an
     * illegal or concurrent transition); the caller turns that into a {@code 409}. This is the
     * atomic guarantee — the {@code WHERE current_state = from} predicate makes the state check
     * race-free, so the optimistic guard read needs no lock.
     */
    int advanceState(Connection cx, String docType, String docId, String from, String to);

    /** Appends one immutable history row within the caller's transaction. History is append-only. */
    void appendHistory(Connection cx, History entry);

    /**
     * One immutable audit-trail entry: who moved a document across which transition, when.
     *
     * @param instanceId the workflow instance id (managed mode), or {@code null} in app mode
     * @param docType    the workflow's {@code document.type}
     * @param docId      the business document key
     * @param transition the transition id that fired
     * @param fromState  the state before the transition
     * @param toState    the state after the transition
     * @param actor      the acting principal's login id (or subject), or {@code null} for a
     *                   system/escalation action
     * @param at         the transition timestamp (one clock reading per command)
     * @param note       an optional free-text note, or {@code null}
     */
    record History(String instanceId, String docType, String docId, String transition,
            String fromState, String toState, String actor, Instant at, String note) {
    }
}
