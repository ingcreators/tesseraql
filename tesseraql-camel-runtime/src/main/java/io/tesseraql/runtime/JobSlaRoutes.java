package io.tesseraql.runtime;

import org.apache.camel.builder.RouteBuilder;

/**
 * The timer that drives the batch SLA sweep (docs/batch-platform.md track E). Every node may
 * fire — the sweeper's per-alert claims make the alerts cluster-unique, so no window claim is
 * needed here; the sweep itself is a handful of reads.
 */
final class JobSlaRoutes extends RouteBuilder {

    private static final System.Logger LOG = System.getLogger(JobSlaRoutes.class.getName());

    private final JobSlaSweeper sweeper;
    private final long periodMillis;

    JobSlaRoutes(JobSlaSweeper sweeper, long periodMillis) {
        this.sweeper = sweeper;
        this.periodMillis = periodMillis;
    }

    @Override
    public void configure() {
        from("timer:tql-job-sla?period=" + periodMillis + "&delay=" + periodMillis)
                .routeId("tql.job.sla")
                .process(exchange -> {
                    int raised = sweeper.sweep();
                    if (raised > 0) {
                        LOG.log(System.Logger.Level.INFO, "Raised {0} batch SLA alert(s)",
                                raised);
                    }
                });
    }
}
