package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.ApplicationName;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * An application declares its own name, and the linter is where that is noticed
 * (docs/cli-surface.md Decision 6).
 *
 * <p>{@code tesseraql.app.name} reads as a label and is an identity: it scopes outbox claims and
 * cluster job claim keys, it is the owner recorded against every job execution and so what
 * {@code ops.app.<name>} grants are checked against, it names the MCP server, and in a stack it is
 * the application's address. It used to default to the literal {@code app}, which made the value
 * required to deploy — {@code AppInstaller} refuses a package without one — and optional to run.
 *
 * <p>The runtime now refuses to start without it, and this rule is why that refusal is rarely met:
 * a missing name is a lint error before it is a failed boot. The two carry
 * {@link ApplicationName#MESSAGE} so they read identically wherever the author meets it first.
 */
final class ApplicationNameRules implements LintRule {

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        String declared = manifest.config().getString("tesseraql.app.name")
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .orElse(null);
        if (declared == null) {
            findings.add(new LintFinding(ApplicationName.MISSING.toString(), ERROR, "config",
                    ApplicationName.MESSAGE));
            return;
        }
        // Segment safety (docs/stack-architecture.md Decision 25): the name becomes the
        // application's address, so a name no URL space can hold is an error before it is a
        // failed boot — same division of labour as the presence check above.
        String violation = ApplicationName.segmentViolation(declared);
        if (violation != null) {
            findings.add(new LintFinding(ApplicationName.UNSAFE_SEGMENT.toString(), ERROR,
                    "config", violation));
        }
    }
}
