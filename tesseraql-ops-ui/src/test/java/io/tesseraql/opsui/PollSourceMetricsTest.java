package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The registry reaches the scrape (docs/poll-source-metrics.md): wired state, failure
 * streaks, and last-poll age as gauge families, jobId as the only label.
 */
class PollSourceMetricsTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void aPollingSourceRendersWiredOneAndNoAgeBeforeItsFirstPoll() {
        PollSourceStatus status = new PollSourceStatus();
        status.polling("orders.intake", "local");

        String out = PollSourceMetrics.render(status, NOW);

        assertThat(out)
                .contains("# TYPE tesseraql_poll_source_wired gauge")
                .contains("tesseraql_poll_source_wired{jobId=\"orders.intake\"} 1")
                .contains("tesseraql_poll_source_consecutive_failures"
                        + "{jobId=\"orders.intake\"} 0")
                // The age of a poll that never happened is not zero; the family is absent
                // until a poll completes, so absent() stays an honest PromQL question.
                .doesNotContain("tesseraql_poll_source_last_poll_age_seconds");
    }

    @Test
    void aSourceSkippedAtWireTimeRendersWiredZeroWithoutTheReasonText() {
        PollSourceStatus status = new PollSourceStatus();
        status.skipped("partner.intake", "sftp",
                "host 'files.partner.example' is not allow-listed");

        String out = PollSourceMetrics.render(status, NOW);

        assertThat(out)
                .contains("tesseraql_poll_source_wired{jobId=\"partner.intake\"} 0")
                // Reasons are unbounded prose; the console page holds the words.
                .doesNotContain("files.partner.example")
                .doesNotContain("sftp");
    }

    @Test
    void failuresAccumulateAndTheAgeComputesFromTheLastPoll() {
        PollSourceStatus status = new PollSourceStatus();
        status.polling("orders.intake", "local");
        status.failed("orders.intake", "boom");
        status.failed("orders.intake", "boom again");

        String out = PollSourceMetrics.render(status,
                status.forJob("orders.intake").orElseThrow().lastPollAt().plusSeconds(90));

        assertThat(out)
                .contains("tesseraql_poll_source_consecutive_failures"
                        + "{jobId=\"orders.intake\"} 2")
                .contains("tesseraql_poll_source_last_poll_age_seconds"
                        + "{jobId=\"orders.intake\"} 90");
    }

    @Test
    void anEmptyRegistryRendersAnEmptyExposition() {
        assertThat(PollSourceMetrics.render(new PollSourceStatus(), NOW)).isEmpty();
    }
}
