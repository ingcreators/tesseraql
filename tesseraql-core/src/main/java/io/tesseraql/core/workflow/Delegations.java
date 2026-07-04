package io.tesseraql.core.workflow;

import java.time.Instant;

/**
 * One-hop absence resolution (roadmap Phase 52): every assignee funnel asks the SAME
 * question the same way, so the semantics cannot drift. Consults exactly the assignee's
 * rule — never the delegate's — so chains and loops are impossible by construction.
 */
public final class Delegations {

    /** An assignment after resolution: who receives it, and who it was meant for. */
    public record Resolved(String assignee, String delegatedFrom) {
    }

    private Delegations() {
    }

    /**
     * Resolves a direct assignee against the store (null store or null assignee resolve to
     * themselves). Self-delegation rules are ignored defensively even if one slips in.
     */
    public static Resolved resolve(DelegationStore store, String tenantId, String assignee) {
        if (store == null || assignee == null) {
            return new Resolved(assignee, null);
        }
        return store.activeDelegate(tenantId, assignee, Instant.now())
                .filter(delegate -> !delegate.equals(assignee))
                .map(delegate -> new Resolved(delegate, assignee))
                .orElse(new Resolved(assignee, null));
    }
}
