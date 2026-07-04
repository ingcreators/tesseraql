package io.tesseraql.core.credential;

import java.util.Optional;

/**
 * TOTP enrollments (roadmap Phase 50 slice 3): one optional second factor per subject.
 * Enrollment is two-phase — a secret is stored unconfirmed, and only a valid code confirms
 * it — and {@link #markUsedStep} is a compare-and-set on the last accepted step: winning it
 * is what accepts a code, so a captured code can never replay inside its window.
 */
public interface TotpStore {

    /** An enrollment; {@code confirmed} is false between begin and the confirming code. */
    record Enrollment(String secret, boolean confirmed, long lastUsedStep) {
    }

    Optional<Enrollment> enrollment(String tenantId, String subject);

    /** Starts (or restarts) enrollment with a fresh secret, unconfirmed. */
    void beginEnrollment(String tenantId, String subject, String secret);

    /** Confirms a pending enrollment; false when none was pending. */
    boolean confirmEnrollment(String tenantId, String subject);

    /** Removes the enrollment entirely (disable); false when none existed. */
    boolean remove(String tenantId, String subject);

    /**
     * Records an accepted step if it is strictly newer than the last — the atomic replay
     * guard. False means the step was already used (or no enrollment exists): refuse.
     */
    boolean markUsedStep(String tenantId, String subject, long step);
}
