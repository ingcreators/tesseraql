package io.tesseraql.opsui;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-node business-day calendar health (docs/jobs.md): one entry per job whose {@code calendar:}
 * could not be resolved at fire time, so the firing ran <em>unfiltered</em>.
 *
 * <p>Failing open is the deliberate fire-time stance — a calendar the runtime cannot read must not
 * strand a scheduled job — but it was also silent apart from one WARN line, so a job that should
 * have been filtered out ran on a holiday and nothing said the gate had been skipped. This is the
 * {@link PollSourceStatus} treatment for it: in-memory per node like the trace ring (calendar
 * resolution is node-local work), read by the dashboard, which raises {@code TQL-OPS-9009}.
 */
public final class CalendarStatus {

    /**
     * One job whose calendar gate was skipped, and why.
     *
     * @param jobId    the job whose firing ran unfiltered
     * @param calendar the calendar name its schedule qualified with
     * @param reason   why it could not be resolved (unknown name, or the holiday read's failure)
     * @param at       when the fail-open last happened
     */
    public record FailOpen(String jobId, String calendar, String reason, Instant at) {
    }

    private final ConcurrentMap<String, FailOpen> byJob = new ConcurrentHashMap<>();

    /** Records a firing that ran unfiltered because its calendar could not be resolved. */
    public void failedOpen(String jobId, String calendar, String reason) {
        byJob.put(jobId, new FailOpen(jobId, calendar, reason, Instant.now()));
    }

    /** Clears a job's fail-open once its calendar resolves again, so a fixed config goes quiet. */
    public void resolved(String jobId) {
        byJob.remove(jobId);
    }

    /** Every job currently failing open, ordered by job id for stable rendering. */
    public List<FailOpen> all() {
        return byJob.values().stream().sorted(Comparator.comparing(FailOpen::jobId)).toList();
    }
}
