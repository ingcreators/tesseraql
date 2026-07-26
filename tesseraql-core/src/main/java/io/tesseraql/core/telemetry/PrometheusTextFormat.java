package io.tesseraql.core.telemetry;

import java.util.Map;
import java.util.TreeMap;

/**
 * Renders an {@link AggregatingMeter} snapshot in the Prometheus text exposition format
 * (version 0.0.4) — JDK-only, resolving roadmap decision point 9 without a new dependency.
 * Metric names normalize dots to underscores; counters gain {@code _total}; histograms emit
 * seconds (converted from the millisecond store) with classic {@code le} bounds.
 */
public final class PrometheusTextFormat {

    /** The scrape response content type for text format 0.0.4. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private PrometheusTextFormat() {
    }

    /** Renders the full exposition for a meter snapshot. */
    public static String render(AggregatingMeter meter) {
        StringBuilder out = new StringBuilder();
        meter.counterSnapshot().forEach((name, samples) -> {
            String metric = sanitize(name) + "_total";
            out.append("# TYPE ").append(metric).append(" counter\n");
            for (AggregatingMeter.CounterSample sample : samples) {
                out.append(metric).append(labels(sample.attributes(), null))
                        .append(' ').append(sample.value()).append('\n');
            }
        });
        meter.histogramSnapshot().forEach((name, samples) -> {
            String metric = sanitize(name) + "_seconds";
            out.append("# TYPE ").append(metric).append(" histogram\n");
            for (AggregatingMeter.HistogramSample sample : samples) {
                long[] cumulative = sample.bucketCounts();
                for (int i = 0; i < AggregatingMeter.BUCKET_BOUNDS_MILLIS.length; i++) {
                    out.append(metric).append("_bucket")
                            .append(labels(sample.attributes(),
                                    seconds(AggregatingMeter.BUCKET_BOUNDS_MILLIS[i])))
                            .append(' ').append(cumulative[i]).append('\n');
                }
                out.append(metric).append("_bucket")
                        .append(labels(sample.attributes(), "+Inf"))
                        .append(' ').append(sample.count()).append('\n');
                out.append(metric).append("_sum").append(labels(sample.attributes(), null))
                        .append(' ').append(sample.sumMillis() / 1000.0).append('\n');
                out.append(metric).append("_count").append(labels(sample.attributes(), null))
                        .append(' ').append(sample.count()).append('\n');
            }
        });
        return out.toString();
    }

    /** One gauge observation: labels plus the value read at scrape time. */
    public record GaugeSample(Map<String, String> attributes, double value) {
    }

    /**
     * Renders one gauge family — a {@code # TYPE} line and one sample per entry — for
     * state that is computed at scrape time rather than aggregated by the meter
     * (docs/poll-source-metrics.md). An empty sample list renders nothing, so an
     * appender never emits a headless family.
     */
    public static String gauge(String name, java.util.List<GaugeSample> samples) {
        if (samples.isEmpty()) {
            return "";
        }
        String metric = sanitize(name);
        StringBuilder out = new StringBuilder();
        out.append("# TYPE ").append(metric).append(" gauge\n");
        for (GaugeSample sample : samples) {
            out.append(metric).append(labels(sample.attributes(), null))
                    .append(' ').append(number(sample.value())).append('\n');
        }
        return out.toString();
    }

    /** Integral values render without a decimal point, like the histogram bounds. */
    private static String number(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    private static String seconds(long millis) {
        double value = millis / 1000.0;
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** {@code {a="x",le="0.5"}} — sorted labels, escaped values, optional le bound. */
    private static String labels(Map<String, String> attributes, String le) {
        if (attributes.isEmpty() && le == null) {
            return "";
        }
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(attributes).entrySet()) {
            if (!first) {
                out.append(',');
            }
            out.append(sanitize(entry.getKey())).append("=\"")
                    .append(escape(entry.getValue())).append('"');
            first = false;
        }
        if (le != null) {
            if (!first) {
                out.append(',');
            }
            out.append("le=\"").append(le).append('"');
        }
        return out.append('}').toString();
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
                    || (i > 0 && c >= '0' && c <= '9');
            out.append(valid ? c : '_');
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
