package io.tesseraql.runtime;

/**
 * The timer that drives the batch SLA sweep (docs/batch-platform.md track E). Every node may
 * fire — the sweeper's per-alert claims make the alerts cluster-unique, so no window claim is
 * needed here; the sweep itself is a handful of reads.
 */
final class JobSlaSweep {

    private static final System.Logger LOG = System.getLogger(JobSlaSweep.class.getName());

    private final JobSlaSweeper sweeper;
    private final long periodMillis;

    JobSlaSweep(JobSlaSweeper sweeper, long periodMillis) {
        this.sweeper = sweeper;
        this.periodMillis = periodMillis;
    }

    void schedule(Schedules schedules) {
        schedules.every("tql.job.sla", periodMillis, () -> {
            int raised = sweeper.sweep();
            if (raised > 0) {
                LOG.log(System.Logger.Level.INFO, "Raised {0} batch SLA alert(s)",
                        raised);
            }
        });
    }
}
