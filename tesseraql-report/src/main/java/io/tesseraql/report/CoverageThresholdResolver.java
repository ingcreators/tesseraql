package io.tesseraql.report;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves coverage gate thresholds (design ch. 14, 18): the app's {@code coverage.thresholds.*}
 * config overrides the Maven goal defaults, so thresholds live with the app in {@code tesseraql.yml}
 * but can still be set or raised from the build.
 *
 * <p>Every key under {@code coverage.thresholds} other than {@code sqlLine} and {@code sqlBranch}
 * is a kind threshold, read as written. A hand-kept allow-list of kinds used to decide which keys
 * were read, and it drifted twice: {@code queue-consume} and {@code decision} were documented as
 * gates the resolver never read, and a typo'd key was dropped without a word, so the gate reported
 * "passed" against a bar the app had set (docs/audit-low-leads.md G22). Whether the kind a key
 * names was measured is {@link io.tesseraql.coverage.CoverageGate}'s to judge, against the kinds
 * the run actually produced.
 */
public final class CoverageThresholdResolver {

    private CoverageThresholdResolver() {
    }

    /**
     * Builds thresholds (as percentages) from {@code coverage.thresholds.sqlLine},
     * {@code coverage.thresholds.sqlBranch}, and every other {@code coverage.thresholds.<kind>}
     * key, falling back to the supplied defaults when a key is absent or no config is available.
     * Kinds without a configured key are not gated.
     */
    public static CoverageThresholds resolve(AppConfig config, double lineDefaultPercent,
            double branchDefaultPercent) {
        double line = config == null
                ? lineDefaultPercent
                : config.getDouble("coverage.thresholds.sqlLine").orElse(lineDefaultPercent);
        double branch = config == null
                ? branchDefaultPercent
                : config.getDouble("coverage.thresholds.sqlBranch").orElse(branchDefaultPercent);
        Map<String, Double> kinds = new LinkedHashMap<>();
        if (config != null
                && config.navigate("coverage.thresholds") instanceof Map<?, ?> declared) {
            for (Object key : declared.keySet()) {
                String kind = String.valueOf(key);
                if ("sqlLine".equals(kind) || "sqlBranch".equals(kind)) {
                    continue;
                }
                config.getDouble("coverage.thresholds." + kind)
                        .ifPresent(percent -> kinds.put(kind, percent / 100.0));
            }
        }
        return new CoverageThresholds(line / 100.0, branch / 100.0, kinds);
    }
}
