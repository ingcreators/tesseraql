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
}
