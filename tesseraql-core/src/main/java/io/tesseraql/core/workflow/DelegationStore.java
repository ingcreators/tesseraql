package io.tesseraql.core.workflow;

import java.time.Instant;
import java.util.Optional;

/**
 * Standing absence rules (roadmap Phase 52, design in docs/delegation.md): one window and
 * one delegate per subject, written only by that subject (the account surface's
 * construction invariant). Resolution is one hop and never a chain — callers consult
 * exactly the assignee's rule, so loops are impossible by construction.
 */
public interface DelegationStore {

    /** One subject's standing rule. */
    record Rule(String delegateSubject, Instant startsAt, Instant endsAt) {

        /** Whether the window covers the instant. */
        public boolean covers(Instant at) {
            return !at.isBefore(startsAt) && at.isBefore(endsAt);
        }
    }

    /** The subject's rule, active or not; empty when none is declared. */
    Optional<Rule> rule(String tenantId, String subject);

    /** The delegate to receive the subject's NEW assignments at {@code at}, if absent. */
    default Optional<String> activeDelegate(String tenantId, String subject, Instant at) {
        return rule(tenantId, subject).filter(rule -> rule.covers(at))
                .map(Rule::delegateSubject);
    }

    /** Creates or replaces the subject's rule. */
    void put(String tenantId, String subject, String delegateSubject, Instant startsAt,
            Instant endsAt);

    /** Removes the subject's rule (a no-op when absent). */
    void clear(String tenantId, String subject);

    /** A rule with its subject, for the operator's visibility panel (slice 2). */
    record Entry(String subject, String delegateSubject, Instant startsAt, Instant endsAt) {
    }

    /**
     * Rules whose window has not ended as of {@code at} (active and upcoming), soonest
     * first — the IAM admin's "why did this land with X?" panel. Default empty for stores
     * that never learned to list.
     */
    default java.util.List<Entry> unexpired(String tenantId, Instant at, int limit) {
        return java.util.List.of();
    }
}
