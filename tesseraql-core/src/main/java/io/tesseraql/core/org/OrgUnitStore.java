package io.tesseraql.core.org;

import java.util.Collection;
import java.util.Set;

/**
 * The managed organizational-unit hierarchy behind data scoping (roadmap Phase 29 slice 2). Units
 * form a tree by {@code parentId}; the store maintains a transitive closure so a subtree query is a
 * plain, portable SELECT (no recursive CTE) — and so a {@code /*%scope%/} fragment can filter rows
 * by {@code descendant_id in (select … from tql_org_closure where ancestor_id in /* my_units *}{@code /
 * (…))}.
 *
 * <p>This is the shared substrate the roadmap pairs across phases: data scoping maps a principal to a
 * row predicate, and approval-workflow assignee resolution (Phase 28) maps a document to principals
 * — duals over the one org graph this store owns. {@link #descendants} is the seam both reuse.
 */
public interface OrgUnitStore {

    /** Inserts or updates a unit (its {@code parentId}, name, and tenant). */
    void upsert(OrgUnit unit);

    /** Removes a unit. The caller rebuilds the closure afterwards. */
    void delete(String unitId);

    /**
     * Recomputes the whole transitive closure from the current {@code parentId} graph: every
     * ancestor/descendant pair, including each unit as its own ancestor at depth 0. Idempotent;
     * cycles are bounded defensively.
     */
    void rebuildClosure();

    /**
     * The ids of every unit at or below the given ancestor units (the union of their subtrees,
     * including the ancestors themselves) — the principal's organizational reach. Empty input yields
     * an empty set.
     */
    Set<String> descendants(Collection<String> ancestorIds);

    /**
     * A unit in the hierarchy.
     *
     * @param id       the unit id
     * @param parentId the parent unit id, or {@code null} for a root
     * @param name     a human label, may be {@code null}
     * @param tenantId the owning tenant, or {@code null} when tenancy is not used
     */
    record OrgUnit(String id, String parentId, String name, String tenantId) {
    }
}
