package io.tesseraql.yaml.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The server-rendered SVG chart wearing the hc-chart skin (Phase 39 slice 4). */
class ChartSvgTest {

    @Test
    void aBarChartRendersTokenColoredRectsWithTooltipsAndGrid() {
        String svg = ChartSvg.render("bar", List.of("Mon", "Tue", "Wed"),
                List.of(2.0, 5.0, 3.0), "Signups");
        assertThat(svg).startsWith("<svg class=\"hc-chart__plot\"");
        assertThat(svg).contains("role=\"img\"").contains("aria-label=\"Signups\"");
        // The gridline group takes the kit's [aria-label$=grid] color rule.
        assertThat(svg).contains("aria-label=\"Signups grid\"");
        assertThat(svg).containsOnlyOnce("aria-label=\"Signups grid\"");
        // Three bars, series-token filled, each carrying its value tooltip.
        assertThat(svg.split("<rect ", -1)).hasSize(4);
        assertThat(svg).contains("fill=\"var(--hc-chart-series-1)\"")
                .contains("<title>Tue: 5</title>");
        // First/last x labels in the label token color.
        assertThat(svg).contains(">Mon</text>").contains(">Wed</text>")
                .contains("fill=\"var(--hc-chart-label)\"");
    }

    @Test
    void aLineChartRendersOnePolyline() {
        String svg = ChartSvg.render("line", List.of("a", "b"), List.of(1.0, 2.0), "Trend");
        assertThat(svg).contains("<polyline points=\"")
                .contains("stroke=\"var(--hc-chart-series-1)\"");
        assertThat(svg).doesNotContain("<rect");
    }

    @Test
    void labelsAreEscaped() {
        String svg = ChartSvg.render("bar", List.of("<b>&\"x\""), List.of(1.0), "T<t>");
        assertThat(svg).doesNotContain("<b>").contains("&lt;b&gt;&amp;&quot;x&quot;");
        assertThat(svg).contains("aria-label=\"T&lt;t&gt;\"");
    }

    @Test
    void emptyDataRendersAnEmptyPlot() {
        String svg = ChartSvg.render("bar", List.of(), List.of(), "Empty");
        assertThat(svg).contains("hc-chart__plot").doesNotContain("<rect")
                .doesNotContain("<polyline");
    }

    @Test
    void theAxisCeilingRoundsUpToANiceStep() {
        assertThat(ChartSvg.niceCeiling(7)).isEqualTo(10.0);
        assertThat(ChartSvg.niceCeiling(23)).isEqualTo(50.0);
        assertThat(ChartSvg.niceCeiling(100)).isEqualTo(100.0);
        assertThat(ChartSvg.niceCeiling(0.4)).isEqualTo(0.5);
        assertThat(ChartSvg.niceCeiling(0)).isEqualTo(0.0);
    }
}
