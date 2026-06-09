package io.tesseraql.runtime;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.TriggerSpec;
import java.util.List;
import java.util.Map;
import org.apache.camel.builder.RouteBuilder;

/**
 * Schedules batch jobs that declare a trigger (design ch. 6.5, 26).
 *
 * <p>{@code fixedDelay} schedules are wired to a Camel timer. {@code cron} schedules are deferred
 * to the quartz-based scheduler in a later phase; such jobs remain runnable on demand.
 */
final class SchedulingRouteBuilder extends RouteBuilder {

    private static final System.Logger LOG = System.getLogger(SchedulingRouteBuilder.class.getName());

    private final OperationsRouteBuilder.JobRunner runner;
    private final List<JobFile> jobs;

    SchedulingRouteBuilder(OperationsRouteBuilder.JobRunner runner, List<JobFile> jobs) {
        this.runner = runner;
        this.jobs = List.copyOf(jobs);
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
                    .process(exchange -> runner.run(jobId, Map.of()));
            LOG.log(System.Logger.Level.INFO, "Scheduled job {0} every {1}ms", jobId, period);
        } else if (schedule.cron() != null && !schedule.cron().isBlank()) {
            from("quartz:tesseraql/" + jobId + "?cron=RAW(" + schedule.cron() + ")")
                    .routeId("schedule." + jobId)
                    .process(exchange -> runner.run(jobId, Map.of()));
            LOG.log(System.Logger.Level.INFO, "Scheduled job {0} with cron {1}", jobId, schedule.cron());
        }
    }
}
