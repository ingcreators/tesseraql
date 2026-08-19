package io.tesseraql.studio.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.policy.Atoms;
import io.tesseraql.security.policy.PolicyEngine;
import java.util.List;

/**
 * Decides whether a caller may edit through Studio: the {@code tql.studio.edit.<name>} atom
 * (docs/studio-shell.md structural decision 4), checked against the caller's permissions with
 * the family wildcard honoured — per application, which the retired global
 * {@code tesseraql.studio.editRoles} allow-list never was, and deny-by-default, which retires
 * the {@code tesseraql.studio.readOnly} master switch (no grant, no edit, nothing to switch
 * off). A framework surface checks atoms, never roles; this class replaced the model's last
 * violation.
 */
final class StudioEdit {

    private static final TqlErrorCode CONFIRM_REQUIRED = new TqlErrorCode(TqlDomain.STUDIO, 4223);

    private final String appName;
    private final boolean confirmApply;

    StudioEdit(String appName, boolean confirmApply) {
        this.appName = appName;
        this.confirmApply = confirmApply;
    }

    /**
     * Whether a draft apply must be acknowledged first
     * ({@code tesseraql.studio.confirmApply}): a general review-the-diff-before-every-apply gate
     * that extends the conflict-only review (Studio backlog D5). Every apply surface honors it —
     * the editor confirms in the compare panel, and the programmatic JSON apply passes
     * {@code confirm=true}; an API that skipped the gate made the policy a suggestion.
     */
    boolean confirmApply() {
        return confirmApply;
    }

    /**
     * Rejects a UI apply that was not acknowledged when {@link #confirmApply()} is on: the editor
     * must review the diff and confirm (or, on a conflict, force) before promoting a draft. A no-op
     * when the gate is off. (422)
     */
    void requireConfirm(boolean acknowledged) {
        if (confirmApply && !acknowledged) {
            throw new TqlException(CONFIRM_REQUIRED,
                    "Review the diff in the compare panel and confirm before applying.");
        }
    }

    /**
     * Whether {@code permissions} (the caller's permission codes, as bound from
     * {@code principal.permissions}) hold this application's edit atom.
     */
    boolean canEdit(Object permissions) {
        if (!(permissions instanceof List<?> list)) {
            return false;
        }
        return Atoms.holds(list.stream().map(String::valueOf).toList(),
                Atoms.STUDIO_EDIT_PREFIX, appName);
    }

    /** Rejects a mutating action when {@code permissions} may not edit (403). */
    void requireEdit(Object permissions) {
        if (!canEdit(permissions)) {
            throw new TqlException(PolicyEngine.FORBIDDEN,
                    "Studio editing requires " + Atoms.STUDIO_EDIT_PREFIX + appName
                            + " (or " + Atoms.STUDIO_EDIT_PREFIX + "*)");
        }
    }
}
