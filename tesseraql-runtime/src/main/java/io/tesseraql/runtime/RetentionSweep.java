package io.tesseraql.runtime;

import io.tesseraql.operations.retention.RetentionSweeper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically removes framework data past its retention period (design ch. 44). Enabled by
 * configuring the sweep interval:
 *
 * <pre>
 * tesseraql:
 *   retention:
 *     sweep: 1h          # how often to sweep (absent = retention disabled)
 *     outbox: 30d        # delivered outbox events older than this are removed (default 30d)
 *     jobs: 90d          # finished batch executions older than this are removed (default 90d)
 *     attachments: 365d  # attachments older than this are removed (absent = no attachment sweep)
 * </pre>
 */
final class RetentionSweep {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweep.class);

    private final RetentionSweeper sweeper;
    private final long sweepMillis;
    private final Duration outboxRetention;
    private final Duration jobRetention;
    private final Duration attachmentRetention;

    RetentionSweep(RetentionSweeper sweeper, long sweepMillis,
            Duration outboxRetention, Duration jobRetention, Duration attachmentRetention) {
        this.sweeper = sweeper;
        this.sweepMillis = sweepMillis;
        this.outboxRetention = outboxRetention;
        this.jobRetention = jobRetention;
        this.attachmentRetention = attachmentRetention;
    }

    void schedule(Schedules schedules) {
        schedules.every("tql.retention", sweepMillis, () -> {
            RetentionSweeper.Result result = sweeper.sweep(outboxRetention, jobRetention,
                    attachmentRetention);
            if (result.outboxEvents() > 0 || result.jobExecutions() > 0
                    || result.attachments() > 0 || result.idempotencyRecords() > 0) {
                LOG.info("Retention sweep removed {} outbox event(s), {} execution(s), "
                        + "{} step(s), {} attachment(s), {} idempotency record(s)",
                        result.outboxEvents(), result.jobExecutions(),
                        result.stepExecutions(), result.attachments(),
                        result.idempotencyRecords());
            }
        });
    }
}
