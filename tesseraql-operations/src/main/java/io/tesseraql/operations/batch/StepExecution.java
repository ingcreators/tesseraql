package io.tesseraql.operations.batch;

import java.time.Instant;

/**
 * A batch step execution record (design ch. 26.3 {@code TQL_STEP_EXECUTION}).
 *
 * @param id               the step execution id
 * @param jobExecutionId   the owning job execution id
 * @param stepId           the step id
 * @param status           the current status
 * @param startTime        when the step started
 * @param endTime          when it finished, or null while running
 * @param durationMs       duration in milliseconds, or null while running
 * @param affectedRows     rows affected (update) or read (query)
 * @param errorMessage     error message, when failed
 */
public record StepExecution(
        String id,
        String jobExecutionId,
        String stepId,
        StepStatus status,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        Integer affectedRows,
        String errorMessage) {
}
