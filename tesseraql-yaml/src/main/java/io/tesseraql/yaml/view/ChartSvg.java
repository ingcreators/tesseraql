package io.tesseraql.yaml.view;

import java.util.List;
import java.util.Locale;

/**
 * Renders a dashboard panel's bar or line chart as deterministic inline SVG wearing the
 * Hypermedia Components chart skin (docs/declarative-views.md, slice 4): the markup is
 * {@code <svg class="hc-chart__plot">} inside the pattern's {@code <figure class="hc-chart">},
 * every color reads a kit token ({@code --hc-chart-series-1}, {@code --hc-chart-grid},
 * {@code --hc-chart-label}), and the gridline group is labelled {@code …grid} so the kit's
 * {@code [aria-label$=grid]} rule colors it. Server-rendered and dependency-free — no client
 * scripting, CSP-clean, and a pure function of the data (reproducibility principle 4).
 */
public final class ChartSvg {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 120;
    private static final int PAD_LEFT = 8;
    private static final int PAD_RIGHT = 8;
    private static final int PAD_TOP = 8;
    private static final int PAD_BOTTOM = 20;
    private static final int GRIDLINES = 3;

    private ChartSvg() {
    }

    /**
     * Renders the chart: one series of {@code values} labelled by {@code labels} (same size),
     * {@code kind} is {@code bar} or {@code line} (default bar). Empty data renders an empty
     * plot area (the panel still shows its title).
     */
    public static String render(String kind, List<String> labels, List<Double> values,
            String title) {
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double top = niceCeiling(max);
        double plotWidth = WIDTH - PAD_LEFT - PAD_RIGHT;
        double plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"hc-chart__plot\" viewBox=\"0 0 ").append(WIDTH).append(' ')
                .append(HEIGHT).append("\" role=\"img\" aria-label=\"")
                .append(escape(title)).append("\" preserveAspectRatio=\"xMidYMid meet\">");
        svg.append("<g aria-label=\"").append(escape(title)).append(" grid\">");
        for (int i = 0; i <= GRIDLINES; i++) {
            double y = PAD_TOP + plotHeight - plotHeight * i / GRIDLINES;
            svg.append("<line x1=\"").append(PAD_LEFT).append("\" y1=\"").append(round(y))
                    .append("\" x2=\"").append(WIDTH - PAD_RIGHT).append("\" y2=\"")
                    .append(round(y))
                    .append("\" stroke=\"currentColor\" stroke-width=\"1\"/>");
        }
        svg.append("</g>");
        int n = values.size();
        if (n > 0 && top > 0) {
            if ("line".equals(kind)) {
                StringBuilder points = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    double x = n == 1
                            ? PAD_LEFT + plotWidth / 2
                            : PAD_LEFT + plotWidth * i / (n - 1);
                    double y = PAD_TOP + plotHeight - plotHeight * values.get(i) / top;
                    if (i > 0) {
                        points.append(' ');
                    }
                    points.append(round(x)).append(',').append(round(y));
                }
                svg.append("<polyline points=\"").append(points)
                        .append("\" fill=\"none\" stroke=\"var(--hc-chart-series-1)\""
                                + " stroke-width=\"2\" stroke-linejoin=\"round\""
                                + " stroke-linecap=\"round\"/>");
            } else {
                double slot = plotWidth / n;
                double barWidth = Math.max(2, slot * 0.6);
                for (int i = 0; i < n; i++) {
                    double height = plotHeight * values.get(i) / top;
                    double x = PAD_LEFT + slot * i + (slot - barWidth) / 2;
                    double y = PAD_TOP + plotHeight - height;
                    svg.append("<rect x=\"").append(round(x)).append("\" y=\"")
                            .append(round(y)).append("\" width=\"").append(round(barWidth))
                            .append("\" height=\"").append(round(height))
                            .append("\" fill=\"var(--hc-chart-series-1)\" rx=\"1\">")
                            .append("<title>").append(escape(label(labels, i))).append(": ")
                            .append(number(values.get(i))).append("</title></rect>");
                }
            }
        }
        if (n > 0) {
            svg.append(axisLabel(label(labels, 0), PAD_LEFT, "start"));
            if (n > 1) {
                svg.append(axisLabel(label(labels, n - 1), WIDTH - PAD_RIGHT, "end"));
            }
        }
        return svg.append("</svg>").toString();
    }

    private static String axisLabel(String text, int x, String anchor) {
        return "<text x=\"" + x + "\" y=\"" + (HEIGHT - 6) + "\" text-anchor=\"" + anchor
                + "\" fill=\"var(--hc-chart-label)\" font-size=\"10\">" + escape(text)
                + "</text>";
    }

    private static String label(List<String> labels, int index) {
        return index < labels.size() && labels.get(index) != null ? labels.get(index) : "";
    }

    /** The axis ceiling: the value rounded up to 1/2/5 × 10^k so bars never overflow. */
    static double niceCeiling(double max) {
        if (max <= 0) {
            return 0;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(max)));
        for (double step : new double[]{1, 2, 5, 10}) {
            if (max <= step * magnitude) {
                return step * magnitude;
            }
        }
        return 10 * magnitude;
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String number(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
