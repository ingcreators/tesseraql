package io.tesseraql.maven;

import io.tesseraql.yaml.governance.GovernanceGate;
import io.tesseraql.yaml.governance.RouteGovernance;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Assesses route governance and applies the review gate (design ch. 51): every route's derived
 * mode, risk score and factors are reported, and routes that need review (by mode or score, per
 * the app's {@code tesseraql.governance} policy) fail the build unless
 * {@code governance/approvals.yml} pins their current source hash.
 */
@Mojo(name = "governance", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class GovernanceMojo extends AbstractMojo {

    /**
     * TQL-GOV-3001: a route that needs review has no valid approval pinning its current source
     * hash in {@code governance/approvals.yml}.
     */
    private static final String VIOLATION = "TQL-GOV-3001";

    /** The external app home to assess. */
    @Parameter(property = "tesseraql.appHome", required = true)
    private File appHome;

    /** Whether unapproved routes fail the build. */
    @Parameter(property = "tesseraql.governance.failOnViolation", defaultValue = "true")
    private boolean failOnViolation;

    @Override
    public void execute() throws MojoFailureException {
        AppManifest manifest = new ManifestLoader().load(appHome.toPath());
        GovernanceGate.Report report = new GovernanceGate(manifest).check(manifest);

        for (RouteGovernance.Assessment assessment : report.assessments()) {
            String line = assessment.routeId() + " [" + assessment.mode() + ", risk "
                    + assessment.riskScore() + "] " + assessment.source();
            if (assessment.riskFactors().isEmpty()) {
                getLog().info(line);
            } else {
                getLog().info(line + " - " + String.join("; ", assessment.riskFactors()));
            }
        }
        for (GovernanceGate.Violation violation : report.violations()) {
            getLog().error(VIOLATION + " " + violation.routeId() + ": " + violation.reason()
                    + ". To approve after review, add to governance/approvals.yml: route="
                    + violation.routeId() + ", sha256=" + violation.sha256());
        }
        getLog().info("TesseraQL governance: " + report.assessments().size() + " route(s), "
                + report.violations().size() + " unapproved");
        if (failOnViolation && !report.violations().isEmpty()) {
            throw new MojoFailureException("TesseraQL governance gate failed: "
                    + report.violations().size() + " route(s) need review");
        }
    }
}
