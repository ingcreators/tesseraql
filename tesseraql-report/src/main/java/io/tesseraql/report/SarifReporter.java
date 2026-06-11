package io.tesseraql.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders findings as SARIF 2.1.0 (design ch. 15), the static-analysis interchange format consumed by
 * CI code-scanning. Findings carry a rule id, level, message, and an optional source location, so
 * coverage gaps and plan-guard issues surface as annotations in the build.
 */
public final class SarifReporter {

    private static final TqlErrorCode REPORT_ERROR = new TqlErrorCode(TqlDomain.REPORT, 1002);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SarifReporter() {
    }

    /**
     * A single finding.
     *
     * @param ruleId    the rule identifier (grouped into the tool's rule list)
     * @param level     SARIF level: {@code error}, {@code warning}, or {@code note}
     * @param message   the human-readable finding text
     * @param filePath  the source file the finding refers to, or null
     * @param startLine the 1-based source line, or null
     */
    public record Finding(String ruleId, String level, String message, String filePath,
            Integer startLine) {
    }

    /** Serializes findings into a SARIF document attributed to {@code toolName}. */
    public static String toSarif(String toolName, List<Finding> findings) {
        Set<String> ruleIds = new LinkedHashSet<>();
        findings.forEach(finding -> ruleIds.add(finding.ruleId()));
        List<Map<String, Object>> rules = new ArrayList<>();
        ruleIds.forEach(id -> rules.add(Map.of("id", id)));

        List<Map<String, Object>> results = new ArrayList<>();
        for (Finding finding : findings) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", finding.ruleId());
            result.put("level", finding.level() == null ? "warning" : finding.level());
            result.put("message", Map.of("text", finding.message()));
            if (finding.filePath() != null) {
                Map<String, Object> physical = new LinkedHashMap<>();
                physical.put("artifactLocation", Map.of("uri", finding.filePath()));
                if (finding.startLine() != null) {
                    physical.put("region", Map.of("startLine", finding.startLine()));
                }
                result.put("locations", List.of(Map.of("physicalLocation", physical)));
            }
            results.add(result);
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", toolName);
        driver.put("rules", rules);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("results", results);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        root.put("version", "2.1.0");
        root.put("runs", List.of(run));

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new TqlException(REPORT_ERROR,
                    "Failed to render SARIF report: " + ex.getMessage());
        }
    }
}
