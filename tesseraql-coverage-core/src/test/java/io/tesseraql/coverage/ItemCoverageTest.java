package io.tesseraql.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class ItemCoverageTest {

    @Test
    void ratioIsCoveredOfDeclared() {
        ItemCoverage coverage = new ItemCoverage("route")
                .declareAll(List.of("a", "b", "c"))
                .cover("a")
                .cover("z"); // covering an undeclared item does not change the denominator

        assertThat(coverage.ratio()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(coverage.uncovered()).containsExactly("b", "c");
        assertThat(coverage.kind()).isEqualTo("route");
    }

    @Test
    void emptyDeclaredIsFullyCovered() {
        assertThat(new ItemCoverage("x").ratio()).isEqualTo(1.0);
    }
}
