package io.tesseraql.core.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.telemetry.PrometheusTextFormat.GaugeSample;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Gauge families rendered at scrape time (docs/poll-source-metrics.md): registry-derived
 * state that the push-side meter never aggregates.
 */
class PrometheusTextFormatTest {

    @Test
    void rendersAGaugeFamilyWithSanitizedNameAndEscapedLabels() {
        String out = PrometheusTextFormat.gauge("tesseraql.poll.source.wired", List.of(
                new GaugeSample(Map.of("jobId", "orders.intake"), 1),
                new GaugeSample(Map.of("jobId", "quote\"weird\nid"), 0)));

        assertThat(out)
                .startsWith("# TYPE tesseraql_poll_source_wired gauge\n")
                .contains("tesseraql_poll_source_wired{jobId=\"orders.intake\"} 1\n")
                .contains("tesseraql_poll_source_wired{jobId=\"quote\\\"weird\\nid\"} 0\n");
    }

    @Test
    void integralValuesRenderWithoutADecimalPointAndFractionsKeepTheirs() {
        String out = PrometheusTextFormat.gauge("age.seconds", List.of(
                new GaugeSample(Map.of(), 42.0),
                new GaugeSample(Map.of("k", "v"), 1.5)));

        assertThat(out)
                .contains("age_seconds 42\n")
                .contains("age_seconds{k=\"v\"} 1.5\n");
    }

    @Test
    void anEmptySampleListRendersNothingNotAHeadlessTypeLine() {
        assertThat(PrometheusTextFormat.gauge("tesseraql.poll.source.wired", List.of()))
                .isEmpty();
    }
}
