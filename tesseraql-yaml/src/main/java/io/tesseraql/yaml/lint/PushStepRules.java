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
     * The delivered name's placeholders against what the job context can resolve
     * (docs/export-hygiene.md P7, item 12), classified by the one class every filename site
     * judges from ({@link io.tesseraql.yaml.app.FilenameTemplates}, so a root means the same
     * here as on an export step's {@code filename:}). A push delivers exactly one file — for a
     * split step, the bundle — so {@code {key}} never resolves and was delivered literally; a
     * root the context does not carry, or a {@code params.<name>} the job never declared,
     * renders {@code _}. {@code {steps.<id>.filename}} is the spelling for the produced file's
     * own name.
     */
    private static void lintDeliveredName(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String as, String source,
            List<LintFinding> findings) {
        if (as == null || as.isBlank()) {
            return;
        }
        io.tesseraql.yaml.app.ExportDeclarations.Site site = io.tesseraql.yaml.app.ExportDeclarations.Site
                .step("", job.definition(), step.id());
        for (io.tesseraql.yaml.app.FilenameTemplates.Finding finding : io.tesseraql.yaml.app.FilenameTemplates
                .classify(as, site, java.util.Set.of())) {
            String path = finding.path();
            String sentence = switch (finding.problem()) {
                // The runtime's grammar is letters, digits, _ and . — anything else stays in
                // the delivered name literally, braces included.
                case MALFORMED -> "placeholder {" + path + "} is not a dotted path of letters,"
                        + " digits, _ and . - the runtime resolves no other spelling and"
                        + " delivers it literally";
                case KEY -> "carries {key}, which a push never resolves - a push delivers one"
                        + " file (for a split step, the bundle); use {steps.<id>.filename} for"
                        + " the produced file's name, or drop as:";
                case UNKNOWN_ROOT -> "placeholder {" + path + "} names no job context root"
                        + " (params, steps, batch, tenant) - it would render _";
                case UNDECLARED_INPUT -> "placeholder {" + path + "} names a parameter the job"
                        + " does not declare under input: - it would render _";
                case UNKNOWN_PATH_PARAMETER -> throw new IllegalStateException(
                        "path is not a job root");
            };
            findings.add(new LintFinding(INCOMPLETE_PUSH_STEP, ERROR, source, "Step '"
                    + step.id() + "': push as: " + sentence));
        }
    }

    /**
     * Statically checks a push step (docs/analytics-experience.md): the transfer reference and
     * target are required, a remote target needs its host and credential, and the delivered
     * name stays a bare filename — separators or placeholder-shaped values would let a YAML
     * scalar steer the write ({@code TQL-YAML-1042}). Host allow-listing stays a runtime
     * refusal ({@code TQL-SEC-4141}): the allow-list is deployment config another environment
     * may declare differently.
     */
    static void lintPushStep(AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String source,
            List<LintFinding> findings) {
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
        lintDeliveredName(job, step, push.as(), source, findings);
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
