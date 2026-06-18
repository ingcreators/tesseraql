package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a caller may edit through Studio (Studio backlog D6 — granular read-only). The
 * all-or-nothing {@code tesseraql.studio.readOnly} master switch is refined by an optional
 * {@code tesseraql.studio.editRoles} allow-list: when set (and Studio is writable), only a caller
 * holding one of those roles may mutate; everyone else is effectively read-only. With no allow-list,
 * any authenticated caller may edit a writable Studio, as before.
 *
 * <p>The decision is per-caller, so it lives in the runtime where the authenticated principal's roles
 * are available — the database-free {@link io.tesseraql.studio.StudioService} keeps enforcing the
 * master read-only switch as defense in depth.
 */
final class StudioAccess {

    private static final TqlErrorCode FORBIDDEN = new TqlErrorCode(TqlDomain.STUDIO, 4031);

    private final boolean writable;
    private final Set<String> editRoles;

    StudioAccess(boolean writable, Set<String> editRoles) {
        this.writable = writable;
        this.editRoles = Set.copyOf(editRoles);
    }

    /** Whether {@code roles} (the caller's roles, as bound from {@code principal.roles}) may edit. */
    boolean canEdit(Object roles) {
        if (!writable) {
            return false;
        }
        if (editRoles.isEmpty()) {
            return true;
        }
        if (!(roles instanceof List<?> list)) {
            return false;
        }
        return list.stream().map(String::valueOf).anyMatch(editRoles::contains);
    }

    /** Rejects a mutating action when {@code roles} may not edit (403). */
    void requireEdit(Object roles) {
        if (!canEdit(roles)) {
            throw new TqlException(FORBIDDEN, editRoles.isEmpty()
                    ? "Studio is read-only; editing is disabled"
                    : "Studio editing requires one of these roles: " + editRoles);
        }
    }
}
