package io.tesseraql.cli;

import io.tesseraql.yaml.governance.GovernanceGate;
import io.tesseraql.yaml.governance.RouteGovernance;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql governance --app <dir>}: assesses route governance and applies the review gate
 * (design ch. 51) — the CLI-native form of the {@code tesseraql:governance} goal. Routes that need
 * review (by mode or risk score) fail unless {@code governance/approvals.yml} pins their current
 * source hash.
 */
@Command(name = "governance", description = "Assess route governance and apply the review gate.")
final class GovernanceCommand implements Callable<Integer> {

    /**
     * TQL-GOV-3001: a route that needs review has no valid approval pinning its current source
     * hash in {@code governance/approvals.yml}.
     */
    private static final String VIOLATION = "TQL-GOV-3001";

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Option(names = {
            "--fail-on-violation"}, negatable = true, description = "Whether unapproved routes fail (default: true).")
    boolean failOnViolation = true;

    @Override
    public Integer call() {
        AppManifest manifest = new ManifestLoader().load(app);
        GovernanceGate.Report report = new GovernanceGate(manifest).check(manifest);
        for (RouteGovernance.Assessment assessment : report.assessments()) {
            String line = assessment.routeId() + " [" + assessment.mode() + ", risk "
                    + assessment.riskScore() + "] " + assessment.source();
            System.out.println(assessment.riskFactors().isEmpty()
                    ? line
                    : line + " - " + String.join("; ", assessment.riskFactors()));
        }
        for (GovernanceGate.Violation violation : report.violations()) {
            System.err.println(VIOLATION + " " + violation.routeId() + ": " + violation.reason()
                    + ". To approve after review, add to governance/approvals.yml: route="
                    + violation.routeId() + ", sha256=" + violation.sha256());
        }
        System.out.println("TesseraQL governance: " + report.assessments().size() + " route(s), "
                + report.violations().size() + " unapproved");
        return failOnViolation && !report.violations().isEmpty() ? 1 : 0;
    }
}
