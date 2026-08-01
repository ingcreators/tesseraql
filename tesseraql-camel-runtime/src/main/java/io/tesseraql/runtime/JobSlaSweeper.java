package io.tesseraql.runtime;

import io.tesseraql.core.util.Durations;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.SlaSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The periodic check behind {@code sla:} (docs/batch-platform.md track E): a job may expect a
 * day's run to have completed by a wall-clock time ({@code completeBy}) or a running execution
 * not to exceed a duration ({@code runningLongerThan}); a miss raises {@code ops.jobSla}
 * through the configured alerts channel. <b>Alert-only</b> — nothing is killed.
 *
 * <p>Each alert is deduplicated cluster-wide through the same claim table scheduled firings
 * use: a too-long execution alerts once (claim keyed by its id), a missed deadline alerts once
 * per business date. Every node may sweep; only one node's alert wins the claim.
 */
final class JobSlaSweeper {

    /** Where alerts go — the runtime binds this to the alerts-channel outbox. */
    @FunctionalInterface
    interface AlertSink {
        void alert(Map<String, Object> payload, String app);
    }

    private final List<JobFile> jobs;
    private final Map<String, String> jobOwners;
    private final String appName;
    private final JobRepository repository;
    private final AlertSink sink;
    private final Clock clock;

    JobSlaSweeper(List<JobFile> jobs, Map<String, String> jobOwners, String appName,
            JobRepository repository, AlertSink sink, Clock clock) {
        this.jobs = List.copyOf(jobs);
        this.jobOwners = Map.copyOf(jobOwners);
        this.appName = appName;
        this.repository = repository;
        this.sink = sink;
        this.clock = clock;
    }

    /** One pass over every job with an {@code sla:}; returns the number of alerts raised. */
    int sweep() {
        int raised = 0;
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (JobFile job : jobs) {
            SlaSpec sla = job.definition().sla();
            if (sla == null) {
                continue;
            }
            String jobId = job.definition().id();
            String owner = jobOwners.getOrDefault(jobId, appName);
            if (sla.runningLongerThan() != null && !sla.runningLongerThan().isBlank()) {
                long thresholdMillis = Durations.toMillis(sla.runningLongerThan());
                for (JobExecution execution : repository.findRunning(jobId)) {
                    if (execution.startTime() == null || execution.startTime()
                            .plusMillis(thresholdMillis).isAfter(now.toInstant())) {
                        continue;
                    }
                    // Once per execution, cluster-wide: the claim key is the execution.
                    if (repository.tryClaimFiring(owner + ":sla-running:" + execution.id(),
                            execution.startTime())) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("jobId", jobId);
                        payload.put("executionId", execution.id());
                        payload.put("kind", "runningLongerThan");
                        payload.put("threshold", sla.runningLongerThan());
                        payload.put("startedAt", execution.startTime().toString());
                        sink.alert(payload, owner);
                        raised++;
                    }
                }
            }
            if (sla.completeBy() != null && !sla.completeBy().isBlank()) {
                LocalTime deadline = LocalTime.parse(sla.completeBy());
                LocalDate today = now.toLocalDate();
                if (now.toLocalTime().isAfter(deadline)
                        && !repository.hasCompleted(jobId, today)) {
                    // Once per business date, cluster-wide: the claim key is the date.
                    Instant window = today.atStartOfDay(now.getZone()).toInstant();
                    if (repository.tryClaimFiring(owner + ":sla-complete:" + jobId, window)) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("jobId", jobId);
                        payload.put("kind", "completeBy");
                        payload.put("deadline", sla.completeBy());
                        payload.put("businessDate", today.toString());
                        sink.alert(payload, owner);
                        raised++;
                    }
                }
            }
        }
        return raised;
    }
}
