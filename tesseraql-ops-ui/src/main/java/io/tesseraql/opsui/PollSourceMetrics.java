package io.tesseraql.opsui;

import io.tesseraql.core.telemetry.PrometheusTextFormat;
import io.tesseraql.core.telemetry.PrometheusTextFormat.GaugeSample;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the {@link PollSourceStatus} registry as Prometheus gauge families at scrape
 * time (docs/poll-source-metrics.md), so a silent poll source is alertable outside the
 * console. {@code jobId} is the only label: source strings can embed connection detail
 * and skip reasons are unbounded prose — the console page holds the words, the gauge
 * value is the alertable fact.
 */
public final class PollSourceMetrics {

    private PollSourceMetrics() {
    }

    /** The full poll-source exposition for one node's registry. */
    public static String render(PollSourceStatus status, Instant now) {
        List<PollSourceStatus.SourceState> sources = status.all();
        List<GaugeSample> wired = new ArrayList<>();
        List<GaugeSample> failures = new ArrayList<>();
        List<GaugeSample> age = new ArrayList<>();
        for (PollSourceStatus.SourceState source : sources) {
            Map<String, String> labels = Map.of("jobId", source.jobId());
            wired.add(new GaugeSample(labels, source.skipped() ? 0 : 1));
            failures.add(new GaugeSample(labels, source.consecutiveFailures()));
            // Absent until a poll completes: the age of a poll that never happened is
            // not zero, and absent() is the right PromQL question for that state.
            if (source.lastPollAt() != null) {
                age.add(new GaugeSample(labels,
                        Duration.between(source.lastPollAt(), now).toMillis() / 1000.0));
            }
        }
        return PrometheusTextFormat.gauge("tesseraql.poll.source.wired", wired)
                + PrometheusTextFormat.gauge("tesseraql.poll.source.consecutive.failures",
                        failures)
                + PrometheusTextFormat.gauge("tesseraql.poll.source.last.poll.age.seconds",
                        age);
    }
}
