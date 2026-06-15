package io.tesseraql.runtime;

import io.tesseraql.operations.batch.JobRepository;
import java.time.Instant;
import org.apache.camel.builder.RouteBuilder;

/**
 * The cluster-safe timer that drives the approval-workflow deadline sweeper (roadmap Phase 28
 * slice 3). Each firing is claimed in {@code tql_job_claim} aligned to its period window — the same
 * mechanism scheduled jobs use — so on a multi-node deployment exactly one node sweeps per interval.
 */
final class WorkflowSweepRoutes extends RouteBuilder {

    private static final System.Logger LOG = System.getLogger(WorkflowSweepRoutes.class.getName());

    private final WorkflowSweeper sweeper;
    private final JobRepository repository;
    private final long periodMillis;
    private final String claimKey;

    WorkflowSweepRoutes(WorkflowSweeper sweeper, JobRepository repository, long periodMillis,
            String appName) {
        this.sweeper = sweeper;
        this.repository = repository;
        this.periodMillis = periodMillis;
        this.claimKey = appName + ":tql-workflow-sweep";
    }

    @Override
    public void configure() {
        from("timer:tql-workflow-sweep?period=" + periodMillis + "&delay=" + periodMillis)
                .routeId("tql.workflow.sweep")
                .process(exchange -> {
                    long now = System.currentTimeMillis();
                    Instant window = Instant.ofEpochMilli(now - (now % periodMillis));
                    if (repository.tryClaimFiring(claimKey, window)) {
                        int escalated = sweeper.sweep();
                        if (escalated > 0) {
                            LOG.log(System.Logger.Level.INFO,
                                    "Escalated {0} overdue workflow task(s)",
                                    escalated);
                        }
                    }
                });
    }
}
