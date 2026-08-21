package io.tesseraql.runtime;

import io.tesseraql.operations.batch.JobRepository;
import java.time.Duration;
import java.util.List;

/**
 * Finishes runs whose owner stopped reporting (docs/audit-hardening.md Decision 6, slice 9).
 *
 * <p>Ownership makes a stranded row recognisable; this is what writes its outcome. Without it a
 * replica killed mid-run leaves a {@code RUNNING} row that {@code overlap: skip} learns to ignore
 * after the liveness window but which sits in the console forever, claiming to be in progress.
 *
 * <p>Every node may sweep, like the SLA sweep beside it. The marking update is conditional on the
 * row still being {@code RUNNING}, so two nodes reaping at once produce one write and one winner —
 * and a run that finished between the read and the write keeps the outcome it reached itself. The
 * reaper never overwrites a verdict a run produced.
 *
 * <p><b>It is a recovery mechanism, not a correctness guarantee.</b> A graceful stop writes its own
 * outcome; what strands a row is SIGKILL, OOM or node loss, and those strand it at any timeout.
 * This notices afterwards, and says so in a reason distinct from a failure the job itself produced.
 */
final class JobReaperSweep {

    private static final System.Logger LOG = System.getLogger(JobReaperSweep.class.getName());

    private final JobRepository repository;
    private final List<String> jobIds;
    private final Duration livenessWindow;
    private final long periodMillis;

    JobReaperSweep(JobRepository repository, List<String> jobIds, Duration livenessWindow,
            long periodMillis) {
        this.repository = repository;
        this.jobIds = List.copyOf(jobIds);
        this.livenessWindow = livenessWindow;
        this.periodMillis = periodMillis;
    }

    void schedule(Schedules schedules) {
        schedules.every("tql.job.reaper", periodMillis, () -> {
            for (String jobId : jobIds) {
                // Per job rather than one sweep over the table: the repository's reads are
                // job-scoped, and a job list is what this runtime knows about. A row for a
                // job this app no longer declares is left alone rather than reaped by a
                // process that knows nothing about it.
                List<String> reaped = repository.reapAbandoned(jobId, livenessWindow);
                if (!reaped.isEmpty()) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Reaped {0} abandoned execution(s) of job {1}: {2}",
                            reaped.size(), jobId, reaped);
                }
            }
        });
    }
}
