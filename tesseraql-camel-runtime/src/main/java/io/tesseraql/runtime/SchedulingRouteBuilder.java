package io.tesseraql.runtime;

import io.tesseraql.core.util.Durations;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.TriggerSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Schedules batch jobs that declare a trigger (design ch. 6.5, 26).
 *
 * <p>Every firing is claimed in the job repository before it runs, so on a multi-node deployment
 * exactly one node executes each scheduled firing: cron firings share Quartz's scheduled fire time
 * as the claim key, and fixed-delay firings are aligned to their period window (which also gives a
 * cluster-wide claim key even though each node's timer started at a different instant).
 */
final class SchedulingRouteBuilder extends RouteBuilder {

    private static final System.Logger LOG = System
            .getLogger(SchedulingRouteBuilder.class.getName());

    /**
     * Whether a claimed firing counts under the job's business-day calendar
     * (docs/batch-platform.md track B). Evaluated after the claim, so on a multi-node
     * deployment exactly one node reads a table-backed calendar per firing. A shifted
     * nominal-day rule additionally names the business date the run is <em>for</em> — "the
     * 5th's run, executed on the 7th" carries the 5th.
     */
    @FunctionalInterface
    interface CalendarGate {

        Decision decide(String jobId, java.time.LocalDate fireDate);

        /**
         * @param counts       whether the firing runs
         * @param businessDate the nominal date a shifted rule runs for, or null to default
         *                     to the firing's local date
         */
        record Decision(boolean counts, java.time.LocalDate businessDate) {

            static final Decision RUNS = new Decision(true, null);
            static final Decision FILTERED = new Decision(false, null);
        }
    }

    private final OpsActions.JobRunner runner;
    private final JobRepository repository;
    private final List<JobFile> jobs;
    private final Map<String, String> claimKeys;
    private final CalendarGate calendarGate;

    /**
     * @param claimKeys the cluster-wide claim key per job id ({@code <app>:<jobId>}), so
     *                  different apps sharing a database never contend for each other's firings
     */
    SchedulingRouteBuilder(OpsActions.JobRunner runner, JobRepository repository,
            List<JobFile> jobs, Map<String, String> claimKeys, CalendarGate calendarGate) {
        this.runner = runner;
        this.repository = repository;
        this.jobs = List.copyOf(jobs);
        this.claimKeys = Map.copyOf(claimKeys);
        this.calendarGate = calendarGate;
    }

    @Override
    public void configure() {
        for (JobFile job : jobs) {
            TriggerSpec trigger = job.definition().trigger();
            if (trigger == null || trigger.schedule() == null) {
                continue;
            }
            schedule(job.definition().id(), trigger.schedule());
        }
    }

    private void schedule(String jobId, TriggerSpec.Schedule schedule) {
        if (schedule.fixedDelay() != null && !schedule.fixedDelay().isBlank()) {
            long period = Durations.toMillis(schedule.fixedDelay());
            from("timer:tql-schedule-" + jobId + "?period=" + period + "&delay=" + period)
                    .routeId("schedule." + jobId)
                    .process(exchange -> runClaimed(jobId, periodWindow(period)));
            LOG.log(System.Logger.Level.INFO, "Scheduled job {0} every {1}ms", jobId, period);
        } else if (schedule.cron() != null && !schedule.cron().isBlank()) {
            from("quartz:tesseraql/" + jobId + "?cron=RAW(" + schedule.cron() + ")")
                    .routeId("schedule." + jobId)
                    .process(exchange -> runClaimed(jobId, quartzFireTime(exchange)));
            LOG.log(System.Logger.Level.INFO, "Scheduled job {0} with cron {1}", jobId,
                    schedule.cron());
        }
    }

    private void runClaimed(String jobId, Instant fireTime) {
        if (!repository.tryClaimFiring(claimKeys.getOrDefault(jobId, jobId), fireTime)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Skipping job {0} firing at {1}: claimed by another node", jobId, fireTime);
            return;
        }
        // The daily-consider model: the cron said when to consider, the calendar says whether
        // this firing counts. A filtered-out firing is not a run — no execution is recorded.
        java.time.LocalDate fireDate = fireTime.atZone(java.time.ZoneId.systemDefault())
                .toLocalDate();
        CalendarGate.Decision decision = calendarGate.decide(jobId, fireDate);
        if (!decision.counts()) {
            LOG.log(System.Logger.Level.INFO,
                    "Skipping job {0} firing for {1}: filtered by its business-day calendar",
                    jobId, fireDate);
            return;
        }
        // A shifted nominal-day rule names the business date the run is FOR (track A meets
        // track B): the 5th's close executed on the 7th records the 5th.
        runner.run(jobId,
                decision.businessDate() == null
                        ? Map.of()
                        : Map.of("businessDate", decision.businessDate().toString()),
                "schedule", null);
    }

    /** Quartz's scheduled fire time is computed from the cron, so it matches across nodes. */
    private static Instant quartzFireTime(Exchange exchange) {
        Date scheduled = exchange.getMessage().getHeader("scheduledFireTime", Date.class);
        if (scheduled == null) {
            scheduled = exchange.getMessage().getHeader("fireTime", Date.class);
        }
        return scheduled != null ? scheduled.toInstant() : Instant.now();
    }

    /** Aligns the firing to its period window so independent node timers claim the same key. */
    private static Instant periodWindow(long periodMillis) {
        long now = System.currentTimeMillis();
        return Instant.ofEpochMilli(now - (now % periodMillis));
    }
}
