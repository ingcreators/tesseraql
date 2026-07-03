package io.tesseraql.core.telemetry;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * A JDK-only in-process metrics store (roadmap Phase 45, decision point 9 resolved in favour of
 * no new dependency): counters and fixed-bucket latency histograms keyed by (name, attributes),
 * snapshotted by the Prometheus text exposition. Bounded by construction — the label sets come
 * from route ids and HTTP methods, not user input — and safe under concurrency (LongAdder /
 * AtomicLongArray; the series maps are ConcurrentHashMaps).
 */
public final class AggregatingMeter implements Meter {

    /**
     * Classic Prometheus latency buckets in milliseconds (exposed as seconds): 5ms .. 10s.
     * An observation above the last bound lands only in +Inf.
     */
    public static final long[] BUCKET_BOUNDS_MILLIS = {
            5, 10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000};

    private final Map<String, Map<String, CounterSeries>> counters = new ConcurrentHashMap<>();
    private final Map<String, Map<String, HistogramSeries>> histograms = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name) {
        Map<String, CounterSeries> series = counters.computeIfAbsent(name,
                key -> new ConcurrentHashMap<>());
        return (delta, attributes) -> series
                .computeIfAbsent(seriesKey(attributes), key -> new CounterSeries(attributes)).value
                .add(delta);
    }

    @Override
    public Histogram histogram(String name) {
        Map<String, HistogramSeries> series = histograms.computeIfAbsent(name,
                key -> new ConcurrentHashMap<>());
        return (valueMillis, attributes) -> series
                .computeIfAbsent(seriesKey(attributes), key -> new HistogramSeries(attributes))
                .record(valueMillis);
    }

    /** One labelled counter value at snapshot time. */
    public record CounterSample(Map<String, String> attributes, long value) {
    }

    /** One labelled histogram at snapshot time: cumulative bucket counts, count, and sum (ms). */
    public record HistogramSample(Map<String, String> attributes, long[] bucketCounts,
            long count, long sumMillis) {
    }

    /** A stable snapshot of every counter series, keyed by metric name. */
    public Map<String, List<CounterSample>> counterSnapshot() {
        Map<String, List<CounterSample>> out = new TreeMap<>();
        counters.forEach((name, series) -> out.put(name, series.values().stream()
                .map(entry -> new CounterSample(entry.attributes, entry.value.sum()))
                .toList()));
        return out;
    }

    /** A stable snapshot of every histogram series (cumulative buckets), keyed by name. */
    public Map<String, List<HistogramSample>> histogramSnapshot() {
        Map<String, List<HistogramSample>> out = new TreeMap<>();
        histograms.forEach((name, series) -> out.put(name, series.values().stream()
                .map(HistogramSeries::sample)
                .toList()));
        return out;
    }

    private static String seriesKey(Map<String, String> attributes) {
        return new TreeMap<>(attributes).toString();
    }

    private static final class CounterSeries {
        final Map<String, String> attributes;
        final LongAdder value = new LongAdder();

        CounterSeries(Map<String, String> attributes) {
            this.attributes = Map.copyOf(attributes);
        }
    }

    private static final class HistogramSeries {
        final Map<String, String> attributes;
        final AtomicLongArray buckets = new AtomicLongArray(BUCKET_BOUNDS_MILLIS.length);
        final LongAdder count = new LongAdder();
        final LongAdder sumMillis = new LongAdder();

        HistogramSeries(Map<String, String> attributes) {
            this.attributes = Map.copyOf(attributes);
        }

        void record(long valueMillis) {
            for (int i = 0; i < BUCKET_BOUNDS_MILLIS.length; i++) {
                if (valueMillis <= BUCKET_BOUNDS_MILLIS[i]) {
                    buckets.incrementAndGet(i);
                    break;
                }
            }
            count.increment();
            sumMillis.add(Math.max(0, valueMillis));
        }

        HistogramSample sample() {
            // Cumulative counts, Prometheus style: bucket[i] = observations <= bound[i].
            long[] cumulative = new long[BUCKET_BOUNDS_MILLIS.length];
            long running = 0;
            for (int i = 0; i < BUCKET_BOUNDS_MILLIS.length; i++) {
                running += buckets.get(i);
                cumulative[i] = running;
            }
            return new HistogramSample(attributes, cumulative, count.sum(), sumMillis.sum());
        }
    }
}
