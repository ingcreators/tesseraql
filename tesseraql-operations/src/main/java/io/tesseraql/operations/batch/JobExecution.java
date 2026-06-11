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
        Instant startTime,
        Instant endTime,
        Long durationMs,
        String exitMessage) {
}
