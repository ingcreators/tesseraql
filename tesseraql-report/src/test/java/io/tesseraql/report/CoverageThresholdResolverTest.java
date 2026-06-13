package io.tesseraql.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoverageThresholdResolverTest {

    @Test
    void configOverridesDefaults() {
        AppConfig config = new AppConfig(Map.of("coverage",
                Map.of("thresholds", Map.of("sqlLine", 90, "sqlBranch", 85))), name -> null);

        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(config, 50, 50);
        assertThat(thresholds.sqlLine()).isCloseTo(0.90, within(1e-9));
        assertThat(thresholds.sqlBranch()).isCloseTo(0.85, within(1e-9));
    }

    @Test
    void fallsBackToDefaultsWhenAbsent() {
        AppConfig config = new AppConfig(Map.of(), name -> null);
        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(config, 60, 70);
        assertThat(thresholds.sqlLine()).isCloseTo(0.60, within(1e-9));
        assertThat(thresholds.sqlBranch()).isCloseTo(0.70, within(1e-9));
    }

    @Test
    void usesDefaultsWhenNoConfig() {
        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(null, 40, 80);
        assertThat(thresholds.sqlLine()).isCloseTo(0.40, within(1e-9));
        assertThat(thresholds.sqlBranch()).isCloseTo(0.80, within(1e-9));
    }
}
