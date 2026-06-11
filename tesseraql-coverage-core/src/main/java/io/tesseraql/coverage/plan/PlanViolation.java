package io.tesseraql.coverage.plan;

import io.tesseraql.core.error.TqlErrorCode;

/**
 * A query plan rule violation (design ch. 46.7).
 *
 * @param code     the error code (TQL-PLAN-*)
 * @param severity {@code error} or {@code warning}
 * @param message  human-readable explanation
 */
public record PlanViolation(TqlErrorCode code, String severity, String message) {
}
