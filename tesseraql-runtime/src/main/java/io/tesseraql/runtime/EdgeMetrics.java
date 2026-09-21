package io.tesseraql.runtime;

import io.tesseraql.core.telemetry.AggregatingMeter;
import io.tesseraql.core.telemetry.Meter;
import io.tesseraql.core.telemetry.PrometheusTextFormat;
import io.tesseraql.core.telemetry.PrometheusTextFormat.GaugeSample;
import io.tesseraql.core.threading.ExecutionLanes;
import io.tesseraql.core.threading.Lane;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * The edge's capacity signals on the scrape (docs/deployment-maturity.md decision 7): how full
 * this node is, and what it refused.
 *
 * <p>There were none. The scrape carried rates and latencies, the pool and the heap, and not the
 * two numbers that say which bound is binding: the requests held in flight against
 * {@code maxInFlight}, and the refusals — {@code TQL-RATE-4293} and {@code 4295} at the member's
 * edge, {@code 4294} and {@code 4296} at the stack's front — which were a WARN line nobody
 * scrapes. A lane's saturation was the same: readable on the console, invisible to an alert rule.
 *
 * <p>Families: {@code tesseraql_http_in_flight{kind}} — {@code request} and {@code stream} at the
 * member's own gate, {@code forward} and {@code streamForward} for the gateway's share of this
 * member when a gateway hosts it — {@code tesseraql_http_refused_total{code}} through the meter,
 * {@code tesseraql_lane_in_use{lane}} and {@code tesseraql_lane_rejected_total{lane}}. The gauges
 * are read at scrape time, like the pool's; nothing samples on a timer.
 */
final class EdgeMetrics {

    /** The refusal counter's meter name; {@code code} is its one label. */
    static final String REFUSED = "tesseraql.http.refused";

    private final Meter meter;
    private final ExecutionLanes lanes;
    private final AtomicReference<HttpAdmission> admission = new AtomicReference<>();
    private volatile IntSupplier forwards;
    private volatile IntSupplier streamForwards;

    EdgeMetrics(Meter meter, ExecutionLanes lanes) {
        this.meter = meter;
        this.lanes = lanes;
    }

    /** The admission gate, once the router exists to install it on. */
    void admission(HttpAdmission gate) {
        admission.set(gate);
    }

    /** The gateway's share of this member: its forwards in flight at the front, by kind. */
    void gatewayForwards(IntSupplier forwards, IntSupplier streamForwards) {
        this.forwards = forwards;
        this.streamForwards = streamForwards;
    }

    /** Counts one refusal by its code — the member's own, or the gateway's for this member. */
    void refused(io.tesseraql.core.error.TqlErrorCode code) {
        meter.counter(REFUSED).increment(Map.of("code", code.toString()));
    }

    /** The lifetime refusal count, every code together, for the sustained-rate alert (9012). */
    static long refusedTotal(AggregatingMeter meter) {
        long total = 0;
        for (AggregatingMeter.CounterSample sample : meter.counterSnapshot()
                .getOrDefault(REFUSED, List.of())) {
            total += sample.value();
        }
        return total;
    }

    /** The gauge and counter families, appended to the metrics exposition. */
    String render() {
        List<GaugeSample> inFlight = new ArrayList<>();
        HttpAdmission gate = admission.get();
        if (gate != null) {
            inFlight.add(new GaugeSample(Map.of("kind", "request"), gate.inFlight()));
            inFlight.add(new GaugeSample(Map.of("kind", "stream"), gate.streamsInFlight()));
        }
        IntSupplier forward = forwards;
        IntSupplier streamForward = streamForwards;
        if (forward != null) {
            inFlight.add(new GaugeSample(Map.of("kind", "forward"), forward.getAsInt()));
        }
        if (streamForward != null) {
            inFlight.add(new GaugeSample(Map.of("kind", "streamForward"),
                    streamForward.getAsInt()));
        }
        List<GaugeSample> inUse = new ArrayList<>();
        List<GaugeSample> rejected = new ArrayList<>();
        if (lanes != null) {
            for (Lane lane : lanes.all()) {
                Map<String, String> labels = Map.of("lane", lane.name());
                inUse.add(new GaugeSample(labels,
                        lane.policy().maxConcurrency() - lane.available()));
                rejected.add(new GaugeSample(labels, lane.rejectedCount()));
            }
        }
        return PrometheusTextFormat.gauge("tesseraql.http.in.flight", inFlight)
                + PrometheusTextFormat.gauge("tesseraql.lane.in.use", inUse)
                + PrometheusTextFormat.counter("tesseraql.lane.rejected", rejected);
    }
}
