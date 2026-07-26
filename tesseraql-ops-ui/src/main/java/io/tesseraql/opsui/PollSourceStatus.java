package io.tesseraql.opsui;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-node poll-source health (docs/poll-source-status.md): one entry per poll-triggered
 * job, fed at wire time — the skip reasons {@code PollingRouteBuilder} used to surface
 * only as log lines — and at import time, with each polled file's fate. In-memory per
 * node like the trace ring: polling is node-local work, and the registry answers "is
 * this node polling".
 */
public final class PollSourceStatus {

    /**
     * How a poll source stands right now. {@code reason} is set only when the source was
     * refused at wire time; {@code lastResult} carries the latest import outcome (a
     * filename on success, an error message on failure).
     */
    public record SourceState(String jobId, String source, boolean skipped, String reason,
            Instant lastPollAt, String lastResult, int consecutiveFailures) {
    }

    /** Consecutive import failures at or above this raise the operational alert. */
    public static final int FAILURE_ALERT_THRESHOLD = 3;

    private final ConcurrentMap<String, SourceState> byJob = new ConcurrentHashMap<>();

    /** Records a source that wired and is polling. */
    public void polling(String jobId, String source) {
        byJob.put(jobId, new SourceState(jobId, source, false, null, null, null, 0));
    }

    /** Records a source refused at wire time, with the reason the operator needs. */
    public void skipped(String jobId, String source, String reason) {
        byJob.put(jobId, new SourceState(jobId, source, true, reason, null, null, 0));
    }

    /** Records a successfully imported file; a success resets the failure streak. */
    public void imported(String jobId, String result) {
        byJob.computeIfPresent(jobId, (id, state) -> new SourceState(id, state.source(),
                false, null, Instant.now(), result, 0));
    }

    /** Records a failed import; failures accumulate until one succeeds. */
    public void failed(String jobId, String error) {
        byJob.computeIfPresent(jobId, (id, state) -> new SourceState(id, state.source(),
                false, null, Instant.now(), error, state.consecutiveFailures() + 1));
    }

    /** The state for one job's poll source, when the job declares one. */
    public Optional<SourceState> forJob(String jobId) {
        return Optional.ofNullable(byJob.get(jobId));
    }

    /** Every tracked source, ordered by job id for stable rendering. */
    public List<SourceState> all() {
        return byJob.values().stream()
                .sorted(Comparator.comparing(SourceState::jobId))
                .toList();
    }
}
