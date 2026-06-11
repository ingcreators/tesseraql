package io.tesseraql.runtime;

import io.tesseraql.operations.retention.RetentionSweeper;
import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically removes framework data past its retention period (design ch. 44). Enabled by
 * configuring the sweep interval:
 *
 * <pre>
 * tesseraql:
 *   retention:
 *     sweep: 1h     # how often to sweep (absent = retention disabled)
 *     outbox: 30d   # delivered outbox events older than this are removed (default 30d)
 *     jobs: 90d     # finished batch executions older than this are removed (default 90d)
 * </pre>
 */
final class RetentionRouteBuilder extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionRouteBuilder.class);

    private final RetentionSweeper sweeper;
    private final long sweepMillis;
    private final Duration outboxRetention;
    private final Duration jobRetention;

    RetentionRouteBuilder(RetentionSweeper sweeper, long sweepMillis,
            Duration outboxRetention, Duration jobRetention) {
        this.sweeper = sweeper;
        this.sweepMillis = sweepMillis;
        this.outboxRetention = outboxRetention;
        this.jobRetention = jobRetention;
    }

    @Override
    public void configure() {
        from("timer:tql-retention?period=" + sweepMillis + "&delay=" + sweepMillis)
                .routeId("tql.retention")
                .process(exchange -> {
                    RetentionSweeper.Result result = sweeper.sweep(outboxRetention, jobRetention);
                    if (result.outboxEvents() > 0 || result.jobExecutions() > 0) {
                        LOG.info("Retention sweep removed {} outbox event(s), {} execution(s), "
                                + "{} step(s)", result.outboxEvents(),
                                result.jobExecutions(), result.stepExecutions());
                    }
                });
    }
}
