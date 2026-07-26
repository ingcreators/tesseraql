package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PollSourceStatusTest {

    @Test
    void tracksWireTimeAndImportTimeFacts() {
        PollSourceStatus status = new PollSourceStatus();
        status.skipped("intake", "sftp", "host not allowed");
        status.polling("orders", "local");

        assertThat(status.forJob("intake")).hasValueSatisfying(state -> {
            assertThat(state.skipped()).isTrue();
            assertThat(state.reason()).isEqualTo("host not allowed");
        });
        assertThat(status.forJob("orders")).hasValueSatisfying(state -> {
            assertThat(state.skipped()).isFalse();
            assertThat(state.lastPollAt()).isNull();
        });
        assertThat(status.forJob("unknown")).isEmpty();
        assertThat(status.all()).extracting(PollSourceStatus.SourceState::jobId)
                .containsExactly("intake", "orders");
    }

    @Test
    void aSuccessResetsTheFailureStreak() {
        PollSourceStatus status = new PollSourceStatus();
        status.polling("orders", "local");
        status.failed("orders", "row rejected");
        status.failed("orders", "row rejected");
        assertThat(status.forJob("orders").orElseThrow().consecutiveFailures()).isEqualTo(2);

        status.imported("orders", "'a.csv' imported");
        PollSourceStatus.SourceState state = status.forJob("orders").orElseThrow();
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.lastResult()).isEqualTo("'a.csv' imported");
        assertThat(state.lastPollAt()).isNotNull();
    }

    @Test
    void importFactsForAnUnwiredJobAreIgnored() {
        // Only wired sources track import facts - a stray id never resurrects an entry.
        PollSourceStatus status = new PollSourceStatus();
        status.imported("ghost", "x");
        status.failed("ghost", "y");
        assertThat(status.all()).isEmpty();
    }
}
