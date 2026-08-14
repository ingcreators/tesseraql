package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * A batch step's {@code push:} block (docs/duckdb-analytics.md).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class PushStepRules {

    private static final String INCOMPLETE_PUSH_STEP = "TQL-YAML-1042";

    private PushStepRules() {
    }

    /**
     * Statically checks a push step (docs/analytics-experience.md): the transfer reference and
     * target are required, a remote target needs its host and credential, and the delivered
     * name stays a bare filename — separators or placeholder-shaped values would let a YAML
     * scalar steer the write ({@code TQL-YAML-1042}). Host allow-listing stays a runtime
     * refusal ({@code TQL-SEC-4141}): the allow-list is deployment config another environment
     * may declare differently.
     */
    static void lintPushStep(AppConfig config, io.tesseraql.yaml.model.PipelineStep step,
            String source, List<LintFinding> findings) {
        io.tesseraql.yaml.model.PushSpec push = step.push();
        if (push.file() == null || push.file().isBlank()) {
            findings.add(new LintFinding(INCOMPLETE_PUSH_STEP, ERROR, source, "Step '" + step.id()
                    + "': push needs file: (a context path resolving to a transfer id, e.g."
                    + " step.report.transferId)"));
        }
        String transport = push.effectiveTransport();
        if (!"local".equals(transport) && !"sftp".equals(transport) && !"ftps".equals(transport)) {
            findings.add(new LintFinding(INCOMPLETE_PUSH_STEP, ERROR, source, "Step '" + step.id()
                    + "': push transport: must be local, sftp, or ftps"));
            return;
        }
        if (push.path() == null || push.path().isBlank()) {
            findings.add(new LintFinding(INCOMPLETE_PUSH_STEP, ERROR, source, "Step '" + step.id()
                    + "': push needs path: (the directory to deliver into)"));
        }
        if (push.isRemote()) {
            if (push.host() == null || push.host().isBlank()) {
                findings.add(new LintFinding(INCOMPLETE_PUSH_STEP, ERROR, source, "Step '"
                        + step.id() + "': a remote push target needs host:"));
            }
            if (push.credential() == null || push.credential().isBlank()) {
                findings.add(new LintFinding(INCOMPLETE_PUSH_STEP, ERROR, source, "Step '"
                        + step.id() + "': a remote push target needs credential: (declared"
                        + " under tesseraql.connectors.push.credentials)"));
            } else if (config.navigate("tesseraql.connectors.push.credentials."
                    + push.credential()) == null) {
                // A warning, not an error: another environment's config may declare it.
                findings.add(new LintFinding(LintCodes.UNDECLARED_CONFIG_REFERENCE, WARNING,
                        source, "Step '"
                                + step.id() + "' references undeclared push credential '"
                                + push.credential() + "'"));
            }
        }
        if (push.as() != null && (push.as().contains("/") || push.as().contains("\\")
                || push.as().contains("..") || push.as().contains("${"))) {
            findings.add(new LintFinding(INCOMPLETE_PUSH_STEP, ERROR, source, "Step '" + step.id()
                    + "': push as: must be a plain file name ({dotted.path} placeholders"
                    + " resolve against the job context)"));
        }
        // The poll side's server-identity nudges, mirrored (docs/connectors.md): an SFTP
        // target without host-key pinning is a warning, an FTPS target without a trust
        // store is an error — the runtime refuses it anyway, so the build says it first.
        if ("sftp".equals(transport)
                && config.getString("tesseraql.connectors.push.knownHostsFile")
                        .filter(value -> !value.isBlank()).isEmpty()) {
            findings.add(new LintFinding(LintCodes.SFTP_HOST_KEY_UNVERIFIED, WARNING, source,
                    "Step '" + step.id()
                            + "': sftp push without tesseraql.connectors.push.knownHostsFile — the"
                            + " server's host key is not verified"));
        }
        if ("ftps".equals(transport)
                && config.navigate("tesseraql.connectors.push.trustStore") == null) {
            findings.add(new LintFinding(LintCodes.FTPS_SERVER_UNVERIFIED, ERROR, source,
                    "Step '" + step.id()
                            + "': ftps push needs tesseraql.connectors.push.trustStore — without it"
                            + " the server certificate is not verified and TLS proves nothing about"
                            + " the peer"));
        }
    }
}
