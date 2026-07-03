package io.tesseraql.core.telemetry;

import java.util.List;

/**
 * Fans every recording out to several meters (roadmap Phase 45): the JDK-only
 * {@link AggregatingMeter} always feeds the Prometheus exposition while an OpenTelemetry
 * meter can push the same series over OTLP.
 */
public final class CompositeMeter implements Meter {

    private final List<Meter> meters;

    public CompositeMeter(Meter... meters) {
        this.meters = List.of(meters);
    }

    @Override
    public Counter counter(String name) {
        List<Counter> counters = meters.stream().map(meter -> meter.counter(name)).toList();
        return (delta, attributes) -> counters.forEach(
                counter -> counter.add(delta, attributes));
    }

    @Override
    public Histogram histogram(String name) {
        List<Histogram> histograms = meters.stream().map(meter -> meter.histogram(name))
                .toList();
        return (value, attributes) -> histograms.forEach(
                histogram -> histogram.record(value, attributes));
    }
}
