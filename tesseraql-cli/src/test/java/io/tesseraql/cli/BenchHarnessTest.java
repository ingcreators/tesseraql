package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The harness's arithmetic (docs/deployment-maturity.md decision 8): exact percentiles from the
 * kept samples — the variant reading them off the meter's fixed buckets is red on the first
 * case — the expectation grammar, and the scrape readout's deltas.
 */
class BenchHarnessTest {

    private static BenchHarness.Result result(long[] millis, Map<Integer, Long> statuses,
            Map<String, Long> refused, long errors) {
        long[] nanos = new long[millis.length];
        for (int i = 0; i < millis.length; i++) {
            nanos[i] = millis[i] * 1_000_000L;
        }
        java.util.Arrays.sort(nanos);
        return new BenchHarness.Result(Instant.EPOCH, Duration.ofSeconds(10), millis.length,
                nanos, statuses, refused, errors, null);
    }

    @Test
    void percentilesAreExactNearestRankOverTheKeptSamples() {
        long[] samples = new long[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = i + 1;
        }
        BenchHarness.Result result = result(samples, Map.of(200, 100L), Map.of(), 0);

        assertThat(result.percentileMillis(0.50)).isEqualTo(50.0);
        assertThat(result.percentileMillis(0.95)).isEqualTo(95.0);
        assertThat(result.percentileMillis(0.99)).isEqualTo(99.0);
        assertThat(result.maxMillis()).isEqualTo(100.0);
        assertThat(result.throughputPerSecond()).isEqualTo(10.0);

        assertThat(result(new long[]{7}, Map.of(200, 1L), Map.of(), 0).percentileMillis(0.99))
                .isEqualTo(7.0);
        assertThat(BenchHarness.percentile(new long[0], 0.5)).isZero();
    }

    @Test
    void refusedAndErrorsReadAsShareOrCount() {
        BenchHarness.Result result = result(new long[]{1, 2, 3, 4}, Map.of(200, 3L, 503, 1L),
                Map.of("TQL-RATE-4293", 1L), 1);

        assertThat(result.refusedTotal()).isEqualTo(1);
        assertThat(result.refusedPercent()).isEqualTo(25.0);
        assertThat(result.errorsPercent()).isEqualTo(20.0);
    }

    @Test
    void expectationsAreEvaluatedAgainstTheRunAndAGrammarSlipIsRefused() {
        BenchHarness.Result result = result(new long[]{10, 20, 30, 40, 200},
                Map.of(200, 4L, 503, 1L), Map.of("TQL-RATE-4293", 1L), 0);

        List<BenchHarness.Check> checks = BenchHarness.evaluate(
                "p50<35ms, p99<=0.3s, max<100ms, refused<1%, refused<=1, throughput>0.4,"
                        + " errors<=0, requests>=5",
                result);

        assertThat(checks).extracting(BenchHarness.Check::expression)
                .containsExactly("p50<35ms", "p99<=0.3s", "max<100ms", "refused<1%",
                        "refused<=1", "throughput>0.4", "errors<=0", "requests>=5");
        assertThat(checks).extracting(BenchHarness.Check::pass)
                .containsExactly(true, true, false, false, true, true, true, true);
        assertThat(checks.get(3).actual()).as("20% refused").isEqualTo(20.0);

        assertThatThrownBy(() -> BenchHarness.evaluate("p95 under 250", result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("p95 under 250").hasMessageContaining("p95<250ms");
        assertThatThrownBy(() -> BenchHarness.evaluate("p95<1%", result))
                .hasMessageContaining("ms or s");
    }

    @Test
    void theScrapeReadoutIsTheCapacitySignalsMovement() {
        BenchHarness.Scrape before = BenchHarness.Scrape.parse("""
                # TYPE tesseraql_http_refused_total counter
                tesseraql_http_refused_total{code="TQL-RATE-4293"} 5
                tesseraql_lane_rejected_total{lane="io"} 2
                tesseraql_route_errors_total{routeId="a",outcome="5xx"} 1
                tesseraql_http_in_flight{kind="request"} 1
                tesseraql_pool_threads_awaiting{pool="main"} 0
                """);
        BenchHarness.Scrape after = BenchHarness.Scrape.parse("""
                tesseraql_http_refused_total{code="TQL-RATE-4293"} 65
                tesseraql_http_refused_total{code="TQL-RATE-4295"} 2
                tesseraql_lane_rejected_total{lane="io"} 2
                tesseraql_route_errors_total{routeId="a",outcome="5xx"} 4
                tesseraql_http_in_flight{kind="request"} 1
                tesseraql_pool_threads_awaiting{pool="main"} 3
                tesseraql_pool_threads_awaiting{pool="reporting"} 1
                """);

        Map<String, Object> readout = BenchHarness.readout(before, after);

        assertThat(readout.get("refused"))
                .isEqualTo(Map.of("TQL-RATE-4293", 60L, "TQL-RATE-4295", 2L));
        assertThat(readout.get("laneRejected")).isEqualTo(0L);
        assertThat(readout.get("routeErrors")).isEqualTo(3L);
        assertThat(readout.get("inFlightAfter")).isEqualTo(1L);
        assertThat(readout.get("poolAwaitingAfter")).isEqualTo(3L);
        assertThat(BenchHarness.readoutLine(readout))
                .contains("refused +62").contains("TQL-RATE-4293 +60").contains("lane rejected +0")
                .contains("pool awaiting 3");
    }

    @Test
    void aTargetEncodesItsQuery() {
        BenchHarness.Target target = new BenchHarness.Target("users.search", "GET",
                "/api/users", new java.util.LinkedHashMap<>(Map.of("q", "sa to")), null, false,
                1);
        assertThat(target.pathWithQuery()).isEqualTo("/api/users?q=sa%20to");
        assertThat(new BenchHarness.Target("a", "GET", "/api/a", Map.of(), null, false, 1)
                .pathWithQuery()).isEqualTo("/api/a");
    }

    @Test
    void theGlossNamesTheBoundBehindEachCode() {
        assertThat(BenchHarness.gloss("TQL-RATE-4293")).contains("maxInFlight");
        assertThat(BenchHarness.gloss("TQL-RATE-4294")).contains("front door");
        assertThat(BenchHarness.gloss("TQL-RATE-4291")).contains("route's own");
    }
}
