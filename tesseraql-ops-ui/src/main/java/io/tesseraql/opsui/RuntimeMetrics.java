package io.tesseraql.opsui;

import io.tesseraql.core.telemetry.PrometheusTextFormat;
import io.tesseraql.core.telemetry.PrometheusTextFormat.GaugeSample;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JVM, garbage-collection, thread and connection-pool gauges
 * (docs/audit-hardening.md Decision 9).
 *
 * <p>There were none. {@code MemoryMXBean}, {@code GarbageCollectorMXBean}, {@code ThreadMXBean}
 * and {@code HikariPoolMXBean} had zero occurrences in any main source, so an operator scraping
 * this framework could see request rates and latency histograms and could not answer "is it out of
 * heap" or "is the pool exhausted" — the two questions asked first in an incident.
 *
 * <p>JDK-only, matching the posture that produced the hand-rolled meter rather than taking a
 * metrics library for it. This is the one signal {@code camel-micrometer} would genuinely have
 * added, and it does not need Micrometer: the beans are in the platform and the exposition already
 * renders gauges.
 *
 * <p>Read at scrape time rather than sampled on a timer. A gauge's value is what it is when asked,
 * and a background sampler would add a thread to keep a number slightly out of date.
 */
public final class RuntimeMetrics {

    /** Hikari exposes its pool through an MXBean; reached reflectively to keep the module free of it. */
    @FunctionalInterface
    public interface PoolStats {

        /** Per-pool {@code active}, {@code idle}, {@code total} and {@code awaiting} counts. */
        Map<String, Map<String, Integer>> read();
    }

    private final PoolStats pools;

    public RuntimeMetrics(PoolStats pools) {
        this.pools = pools;
    }

    /** The whole family set, appended to the metrics exposition. */
    public String render() {
        return heap() + threads() + gc() + pools();
    }

    private static String heap() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        return PrometheusTextFormat.gauge("tesseraql.jvm.memory.used.bytes", List.of(
                new GaugeSample(Map.of("area", "heap"), heap.getUsed()),
                new GaugeSample(Map.of("area", "nonheap"), nonHeap.getUsed())))
                + PrometheusTextFormat.gauge("tesseraql.jvm.memory.committed.bytes", List.of(
                        new GaugeSample(Map.of("area", "heap"), heap.getCommitted()),
                        new GaugeSample(Map.of("area", "nonheap"), nonHeap.getCommitted())))
                // The max is -1 when unbounded, which is a truthful reading rather than a gap.
                + PrometheusTextFormat.gauge("tesseraql.jvm.memory.max.bytes", List.of(
                        new GaugeSample(Map.of("area", "heap"), heap.getMax()),
                        new GaugeSample(Map.of("area", "nonheap"), nonHeap.getMax())));
    }

    private static String threads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return PrometheusTextFormat.gauge("tesseraql.jvm.threads", List.of(
                new GaugeSample(Map.of("state", "live"), threads.getThreadCount()),
                new GaugeSample(Map.of("state", "daemon"), threads.getDaemonThreadCount()),
                new GaugeSample(Map.of("state", "peak"), threads.getPeakThreadCount())));
    }

    private static String gc() {
        List<GaugeSample> counts = new ArrayList<>();
        List<GaugeSample> times = new ArrayList<>();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, String> labels = Map.of("collector", collector.getName());
            counts.add(new GaugeSample(labels, collector.getCollectionCount()));
            times.add(new GaugeSample(labels, collector.getCollectionTime()));
        }
        return PrometheusTextFormat.gauge("tesseraql.jvm.gc.collections", counts)
                + PrometheusTextFormat.gauge("tesseraql.jvm.gc.time.millis", times);
    }

    private String pools() {
        if (pools == null) {
            return "";
        }
        List<GaugeSample> active = new ArrayList<>();
        List<GaugeSample> idle = new ArrayList<>();
        List<GaugeSample> total = new ArrayList<>();
        List<GaugeSample> awaiting = new ArrayList<>();
        pools.read().forEach((name, stats) -> {
            Map<String, String> labels = Map.of("pool", name);
            active.add(new GaugeSample(labels, stats.getOrDefault("active", 0)));
            idle.add(new GaugeSample(labels, stats.getOrDefault("idle", 0)));
            total.add(new GaugeSample(labels, stats.getOrDefault("total", 0)));
            awaiting.add(new GaugeSample(labels, stats.getOrDefault("awaiting", 0)));
        });
        return PrometheusTextFormat.gauge("tesseraql.pool.connections.active", active)
                + PrometheusTextFormat.gauge("tesseraql.pool.connections.idle", idle)
                + PrometheusTextFormat.gauge("tesseraql.pool.connections.total", total)
                // The one that says the pool is the bottleneck rather than merely busy.
                + PrometheusTextFormat.gauge("tesseraql.pool.threads.awaiting", awaiting);
    }
}
