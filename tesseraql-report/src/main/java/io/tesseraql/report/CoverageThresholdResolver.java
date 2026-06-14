package io.tesseraql.report;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves coverage gate thresholds (design ch. 14, 18): the app's {@code coverage.thresholds.*}
 * config overrides the Maven goal defaults, so thresholds live with the app in {@code tesseraql.yml}
 * but can still be set or raised from the build.
 */
public final class CoverageThresholdResolver {

    /** The item-coverage kinds a {@code coverage.thresholds.<kind>} percentage can gate. */
    private static final List<String> KINDS = List.of("assertion", "iam-contract", "route",
            "security", "api-key", "mtls", "saml", "oidc", "scim", "validation", "notification",
            "http-call", "file-poll", "document", "message", "mcp", "mcp-resource", "mcp-ui");

    private CoverageThresholdResolver() {
    }

    /**
     * Builds thresholds (as percentages) from {@code coverage.thresholds.sqlLine},
     * {@code coverage.thresholds.sqlBranch}, and the per-kind keys (for example
     * {@code coverage.thresholds.route}), falling back to the supplied defaults when a key is
     * absent or no config is available. Kinds without a configured key are not gated.
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
        if (config != null) {
            for (String kind : KINDS) {
                config.getDouble("coverage.thresholds." + kind)
                        .ifPresent(percent -> kinds.put(kind, percent / 100.0));
            }
        }
        return new CoverageThresholds(line / 100.0, branch / 100.0, kinds);
    }
}
