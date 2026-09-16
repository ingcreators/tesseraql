package io.tesseraql.core.credential;

import java.util.List;
import java.util.Optional;

/**
 * TOTP enrollments (roadmap Phase 50 slice 3): one optional second factor per subject.
 * Enrollment is two-phase — a secret is stored unconfirmed, and only a valid code confirms
 * it — and {@link #markUsedStep} is a compare-and-set on the last accepted step: winning it
 * is what accepts a code, so a captured code can never replay inside its window.
 *
 * <p>The recovery codes ride the enrollment (docs/credential-lifecycle.md): minted in plain
 * beside the pending secret, activated as hashes by the same write that confirms, and removed
 * with the enrollment. No call leaves the factor enabled without its codes.
 */
public interface TotpStore {

    /**
     * An enrollment; {@code confirmed} is false between begin and the confirming code, and
     * {@code pendingRecovery} holds the plain recovery codes only while it is.
     */
    record Enrollment(String secret, boolean confirmed, long lastUsedStep,
            String pendingRecovery) {
    }

    Optional<Enrollment> enrollment(String tenantId, String subject);

    /**
     * Starts (or restarts) enrollment: a fresh secret and its plain recovery codes, stored
     * unconfirmed in one write. The plain codes are held exactly like the pending secret in
     * the same row and shown to the owner until the confirming code activates them.
     */
    void beginEnrollment(String tenantId, String subject, String secret,
            String plainRecoveryCodes);

    /**
     * Confirms a pending enrollment and activates its recovery codes in one transaction: the
     * given SHA-256 hex hashes replace the subject's codes, the plain pending copy is dropped,
     * and {@code confirmed_at} is set — or nothing is written. False when no enrollment was
     * pending.
     */
    boolean confirmEnrollment(String tenantId, String subject, List<String> recoveryCodeHashes);

    /** Consumes (deletes) one recovery code by hash — single-use; false when absent. */
    boolean consumeRecoveryCode(String tenantId, String subject, String codeHash);

    /** Removes the enrollment and its recovery codes (disable); false when none existed. */
    boolean remove(String tenantId, String subject);

    /**
     * Records an accepted step if it is strictly newer than the last — the atomic replay
     * guard. False means the step was already used (or no enrollment exists): refuse.
     */
    boolean markUsedStep(String tenantId, String subject, long step);
}
