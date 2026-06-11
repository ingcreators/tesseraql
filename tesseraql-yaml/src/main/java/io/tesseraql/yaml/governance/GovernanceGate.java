package io.tesseraql.yaml.governance;

import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.governance.RouteGovernance.Assessment;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The governance review gate (design ch. 51). Policy comes from the app config:
 *
 * <pre>
 * tesseraql:
 *   governance:
 *     maxRiskScore: 5              # routes scoring higher need an approval (absent = no score gate)
 *     requireApproval: [advanced]  # modes that always need an approval
 * </pre>
 *
 * <p>Approvals live in {@code governance/approvals.yml}, pinning each reviewed route to the
 * SHA-256 of its source at review time:
 *
 * <pre>
 * approvals:
 *   - route: users.deactivate
 *     sha256: 4f2a...
 *     approvedBy: reviewer-id
 * </pre>
 *
 * <p>A route that needs review passes only while its current hash matches its approval - editing
 * an approved route invalidates the approval, forcing a re-review. Violations report the current
 * hash so the reviewer can approve by adding it to the ledger.
 */
public final class GovernanceGate {

    /** A route that needs review but has no valid approval. */
    public record Violation(String routeId, String source, String mode, int riskScore,
            List<String> riskFactors, String reason, String sha256) {
    }

    /** The gate outcome: every assessment plus the unapproved ones. */
    public record Report(List<Assessment> assessments, List<Violation> violations) {
    }

    private final Integer maxRiskScore;
    private final List<String> requireApproval;

    public GovernanceGate(AppManifest manifest) {
        this.maxRiskScore = manifest.config().getString("tesseraql.governance.maxRiskScore")
                .map(Integer::parseInt).orElse(null);
        this.requireApproval = approvalModes(manifest);
    }

    private static List<String> approvalModes(AppManifest manifest) {
        Object modes = manifest.config().navigate("tesseraql.governance.requireApproval");
        if (modes instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** Assesses every route and applies the review gate. */
    public Report check(AppManifest manifest) {
        Map<String, String> approvals = loadApprovals(manifest.appHome());
        List<Assessment> assessments = RouteGovernance.assess(manifest);
        List<Violation> violations = new ArrayList<>();
        for (Assessment assessment : assessments) {
            String reason = reviewReason(assessment);
            if (reason == null) {
                continue;
            }
            String approvedSha = approvals.get(assessment.routeId());
            if (approvedSha == null) {
                violations.add(violation(assessment, reason + " and has no approval"));
            } else if (!approvedSha.equalsIgnoreCase(assessment.sha256())) {
                violations.add(violation(assessment,
                        reason + " and was edited since its approval (re-review required)"));
            }
        }
        return new Report(assessments, violations);
    }

    private String reviewReason(Assessment assessment) {
        if (requireApproval.contains(assessment.mode())) {
            return "mode '" + assessment.mode() + "' requires approval";
        }
        if (maxRiskScore != null && assessment.riskScore() > maxRiskScore) {
            return "risk score " + assessment.riskScore() + " exceeds the maximum " + maxRiskScore;
        }
        return null;
    }

    private static Violation violation(Assessment assessment, String reason) {
        return new Violation(assessment.routeId(), assessment.source(), assessment.mode(),
                assessment.riskScore(), assessment.riskFactors(), reason, assessment.sha256());
    }

    /** Loads {@code governance/approvals.yml}: route id to the approved source hash. */
    static Map<String, String> loadApprovals(Path appHome) {
        Path file = appHome.resolve("governance/approvals.yml");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, Object> tree = new SimpleYamlParser().parseTree(file);
        Object entries = tree.get("approvals");
        Map<String, String> approvals = new HashMap<>();
        if (entries instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map && map.get("route") != null) {
                    approvals.put(String.valueOf(map.get("route")),
                            String.valueOf(map.get("sha256")));
                }
            }
        }
        return approvals;
    }
}
