package io.tesseraql.operations.batch;

import java.time.Instant;

/**
 * A batch job execution record (design ch. 26.3 {@code TQL_JOB_EXECUTION}).
 *
 * @param id          the execution id
 * @param jobId       the job id
 * @param appName     the application name
 * @param status      the current status
 * @param triggerType how the run was triggered (e.g. {@code manual}, {@code schedule})
 * @param triggeredBy who triggered a manual run (the principal's login id), or null for
 *                    scheduled and system-initiated runs
 * @param businessDate the business date the run was for (docs/batch-platform.md):
 *                    defaulted from the firing's local date, overridable on manual runs
 * @param startTime   when the execution started
 * @param endTime     when it finished, or null while running
 * @param durationMs  total duration in milliseconds, or null while running
 * @param exitMessage exit / error message, when present
 */
public record JobExecution(
        String id,
        String jobId,
        String appName,
        JobStatus status,
        String triggerType,
        String triggeredBy,
        java.time.LocalDate businessDate,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        String exitMessage,
        String ownerNode,
        Instant heartbeatAt) {

    /**
     * Whether this run's owner has reported inside {@code window}
     * (docs/audit-hardening.md Decision 6).
     *
     * <p>A null heartbeat reads as alive rather than dead, and that is deliberate: rows written
     * before this column existed have no pulse to judge, and treating them as dead would let a
     * reaper kill a run that a still-running older process owns. The conservative reading keeps
     * today's behaviour for those rows — they stay wedged, which is the bug this change stops
     * creating rather than one it retroactively repairs.
     */
    public boolean ownerAlive(Instant now, java.time.Duration window) {
        return heartbeatAt == null || heartbeatAt.isAfter(now.minus(window));
    }
}
