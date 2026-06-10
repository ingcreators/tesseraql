package io.tesseraql.maven;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.yaml.config.AppConfig;

/**
 * Resolves coverage gate thresholds (design ch. 14, 18): the app's {@code coverage.thresholds.*}
 * config overrides the Maven goal defaults, so thresholds live with the app in {@code tesseraql.yml}
 * but can still be set or raised from the build.
 */
final class CoverageThresholdResolver {

    private CoverageThresholdResolver() {
    }

    /**
     * Builds thresholds (as percentages) from {@code coverage.thresholds.sqlLine} and
     * {@code coverage.thresholds.sqlBranch}, falling back to the supplied defaults when a key is
     * absent or no config is available.
     */
    static CoverageThresholds resolve(AppConfig config, double lineDefaultPercent,
            double branchDefaultPercent) {
        double line = config == null ? lineDefaultPercent
                : config.getDouble("coverage.thresholds.sqlLine").orElse(lineDefaultPercent);
        double branch = config == null ? branchDefaultPercent
                : config.getDouble("coverage.thresholds.sqlBranch").orElse(branchDefaultPercent);
        return CoverageThresholds.ofPercent(line, branch);
    }
}
