package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.JobDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The batch-job family: a job document's own shape and its pipeline steps.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class JobRules implements LintRule {

    private static final String STEP_RUNS_ONE_UNIT = "TQL-FIELD-2008";

    private static final String POLL_SOURCE_WITHOUT_ALLOWED_PATHS = "TQL-SEC-4093";

    // A poll trigger says a file arrives; nothing says how to read it or what to write.
    private static final String POLL_JOB_WITHOUT_IMPORT = "TQL-YAML-1055";

    private static final String POLL_HOST_NOT_ALLOWED = "TQL-SEC-4080";

    /**
     * TQL-YAML-1310: a remote poll source with no exclusive-consumption store, so every replica
     * imports every file (docs/audit-hardening.md Decision 4).
     */
    private static final String POLL_WITHOUT_EXCLUSIVE_CONSUMPTION = "TQL-YAML-1310";

    private static final String POLL_UNDECLARED_CREDENTIAL = "TQL-SEC-4081";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            lintJob(appHome, manifest.config(), job, context.calendars(), findings);
        }
    }

    /**
     * Statically checks a batch job's pipeline steps (roadmap Phase 20, 26): a step declares
     * at most one binding arm ({@code sql:} or {@code http:}); notify steps lint like a
     * route's, and an http: step lints its egress against the allow-list (deny by default).
     */
    void lintJob(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.calendar.Calendars calendars, List<LintFinding> findings) {
        String source = appHome.relativize(job.source()).toString().replace('\\', '/');
        UnknownKeyRules.lintUnknownKeys(context, appHome, job.source(), JobDefinition.class,
                Set.of(), findings);
        if (job.definition().trigger() != null && job.definition().trigger().poll() != null) {
            lintPollJob(config, job, source, findings);
        }
        if (job.definition().trigger() != null && job.definition().trigger().schedule() != null) {
            CalendarRules.lintScheduleCalendar(job, job.definition().trigger().schedule(),
                    calendars, source,
                    findings);
        }
        OverlapSlaRules.lintOverlapAndSla(job, source, findings);
        for (io.tesseraql.yaml.model.PipelineStep step : job.definition().pipeline()) {
            // The axes (docs/unified-sources.md decision 12): the binding arm reads or writes,
            // an output block says what to do with a result, chunk: processes. A step declares
            // at least one — and, because the executor dispatches one unit per step, at most
            // one output, with the plain-sql-arm-plus-export: extraction pair as the one
            // designed combination (checked below).
            boolean writes = step.sql() != null && step.sql().isSql();
            boolean calls = step.sql() != null && step.sql().declaresHttp();
            boolean arm = step.sql() != null && !StepRules.isGuardOnly(step.sql());
            int outputs = (step.notification() == null ? 0 : 1) + (step.export() == null ? 0 : 1)
                    + (step.push() == null ? 0 : 1);
            boolean processing = step.chunk() != null;
            if (!arm && !processing && outputs == 0) {
                findings.add(new LintFinding(LintCodes.STEP_WORK_SHAPE, ERROR, source, "Step '"
                        + step.id() + "' declares no work - a step needs a binding (sql:, http:),"
                        + " an output (export:, push:, notify:), or chunk:"));
                continue;
            }
            if (writes && calls) {
                findings.add(new LintFinding(LintCodes.STEP_WORK_SHAPE, ERROR, source, "Step '"
                        + step.id() + "' declares two bindings - sql: and http: are both the"
                        + " step's own work, and a step does one thing"));
                continue;
            }
            if (processing && arm) {
                findings.add(new LintFinding(LintCodes.STEP_WORK_SHAPE, ERROR, source, "Step '"
                        + step.id() + "' declares chunk: beside a binding - a chunk step's work"
                        + " is its reader:/writer:, so the step has no arm of its own"));
                continue;
            }
            // The executor runs ONE unit per step, chosen in dispatch order (http arm, notify,
            // chunk, export, push, then plain sql) — so most block combinations either fail at
            // 3am or silently drop a block the author wrote. The one designed pair is a plain
            // sql arm consumed by export: as its extraction. Everything else is refused here,
            // where the author is still looking (TQL-FIELD-2008, one rule).
            if (outputs > 1) {
                findings.add(new LintFinding(STEP_RUNS_ONE_UNIT, ERROR, source, "Step '"
                        + step.id() + "' declares " + outputs + " output blocks - the executor"
                        + " runs one unit per step, and the others would be dropped in silence."
                        + " Split them into steps"));
                continue;
            }
            if (step.sql() != null && step.notification() != null) {
                findings.add(new LintFinding(STEP_RUNS_ONE_UNIT, ERROR, source, "Step '"
                        + step.id() + "' declares sql: beside notify: - a notify step carries"
                        + " no binding (the executor refuses it at run time). Compute in a"
                        + " prior step and reference its result from the notification"));
                continue;
            }
            if (step.sql() != null && step.push() != null) {
                findings.add(new LintFinding(STEP_RUNS_ONE_UNIT, ERROR, source, "Step '"
                        + step.id() + "' declares sql: beside push: - a push delivers an"
                        + " earlier transfer (file: steps.<id>.transferId), and the binding"
                        + " would never run. Split them into steps"));
                continue;
            }
            if (calls && step.export() != null) {
                findings.add(new LintFinding(STEP_RUNS_ONE_UNIT, ERROR, source, "Step '"
                        + step.id() + "' declares an http: arm beside export: - an export's"
                        + " extraction is a plain sql: arm, and the export would be dropped in"
                        + " silence. Spool the acquisition and export from a later step"));
                continue;
            }
            StepRules.lintStepDatasource(config, step, source, findings);
            // Each remaining axis is linted on its own.
            if (step.notification() != null) {
                MessagingRules.lintNotifySpec(config, step.id(), step.notification(), source,
                        findings, context.functions());
            }
            if (calls) {
                HttpSourceRules.lintHttpCall(config, step.id(), step.sql().http().call(), source,
                        findings);
                StepRules.lintHttpMode(step, source, findings);
            }
            StepRules.lintStepEnrich(job, step, source, findings);
            if (step.chunk() != null) {
                ChunkRules.lintChunk(context, job, step, source, findings);
            }
            if (step.export() != null) {
                ExportRules.lintExportStep(context, job, step, source, findings);
            }
            if (step.push() != null) {
                PushStepRules.lintPushStep(config, step, source, findings);
            }
        }
    }

    /**
     * Statically checks a {@code poll:}-triggered file-import job (roadmap Phase 26): the source is
     * a known kind with a path, a remote source has an allow-listed host
     * ({@code TQL-SEC-4080}, deny by default) and a configured credential ({@code TQL-SEC-4081}, a
     * warning), an SFTP source should verify the server's host key against
     * {@code tesseraql.connectors.poll.knownHostsFile} ({@code TQL-SEC-4084}, a warning), an FTPS
     * source must verify the server certificate against
     * {@code tesseraql.connectors.poll.trustStore} ({@code TQL-SEC-4085}, an error — unlike SSH
     * host keys there is no first-use posture to preserve), and the job carries an
     * {@code import:} block whose per-row SQL file exists.
     */
    private void lintPollJob(AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.PollSpec poll = job.definition().trigger().poll();
        if (job.definition().trigger().schedule() != null) {
            findings.add(new LintFinding(LintCodes.INVALID_JOB_TRIGGER, ERROR, source,
                    "Job '" + job.definition().id()
                            + "' declares both a schedule and a poll trigger; declare one"));
        }
        String kind = poll.effectiveTransport();
        if (!List.of("local", "sftp", "ftps").contains(kind)) {
            findings.add(new LintFinding(LintCodes.INVALID_JOB_TRIGGER, ERROR, source,
                    "Poll trigger transport must be local, sftp, or ftps (was '"
                            + poll.transport() + "')"));
        }
        if (poll.path() == null || poll.path().isBlank()) {
            findings.add(new LintFinding(LintCodes.INVALID_JOB_TRIGGER, ERROR, source,
                    "Poll trigger needs a path: (the directory to poll)"));
        }
        // Values that reach the endpoint URI. delay throws inside wire() where the failure is
        // logged and the job dropped, so the app boots healthy with a route that never runs;
        // port fails at connect. Both are better answered here.
        if (poll.delay() != null && !poll.delay().isBlank()) {
            try {
                io.tesseraql.core.util.Durations.toMillis(poll.delay());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding(LintCodes.INVALID_JOB_TRIGGER, ERROR,
                        source,
                        "Poll trigger delay '" + poll.delay() + "' is not a duration — the job"
                                + " would be dropped at startup, leaving the app healthy with"
                                + " nothing arriving"));
            }
        }
        if (poll.port() != null && (poll.port() < 1 || poll.port() > 65535)) {
            findings.add(new LintFinding(LintCodes.INVALID_JOB_TRIGGER, ERROR, source,
                    "Poll trigger port " + poll.port() + " is outside 1-65535"));
        }
        // The read lock a poll source carries is a write-stability check, not exclusion: a file
        // is read once its fingerprint stops changing, which says nothing about whether another
        // replica is reading it too. So three replicas polling one drop directory each import
        // every file unless consumeOnce: claims it in the database first.
        //
        // This used to be scoped to the remote transports, because the library's local strategy
        // extended its marker-file one and did write an atomic lock file. The connectors are the
        // framework's own now (docs/camel-removal.md slice 1) and none of them writes a marker,
        // so the exemption outlived its reason — and a local source is exactly where an author
        // would least expect silent duplication. A warning rather than an error because a
        // single-node deployment is a real deployment, and because turning it on changes what a
        // re-sent file means.
        if (!poll.consumesOnce()) {
            findings.add(new LintFinding(POLL_WITHOUT_EXCLUSIVE_CONSUMPTION, WARNING, source,
                    "Poll source '" + job.definition().id() + "' polls " + kind
                            + ", which has no server-side exclusion, and declares no"
                            + " consumeOnce: true — every replica will import every file"));
        }
        if (!poll.isRemote()) {
            // Keys that belong to a remote source parse cleanly and are then discarded, so an
            // author converting a job between kinds gets no signal that they now mean nothing.
            if (poll.host() != null && !poll.host().isBlank()) {
                findings.add(
                        new LintFinding(LintCodes.INVALID_JOB_TRIGGER, WARNING, source,
                                "Poll trigger source '" + kind
                                        + "' ignores host: — remove it or use a"
                                        + " remote source"));
            }
            if (poll.credential() != null && !poll.credential().isBlank()) {
                findings.add(
                        new LintFinding(LintCodes.INVALID_JOB_TRIGGER, WARNING, source,
                                "Poll trigger source '" + kind
                                        + "' ignores credential: — remove it or"
                                        + " use a remote source"));
            }
            if (config.navigate("tesseraql.connectors.poll.allowedPaths") == null) {
                findings.add(new LintFinding(POLL_SOURCE_WITHOUT_ALLOWED_PATHS, ERROR, source,
                        "Local poll source has no tesseraql.connectors.poll.allowedPaths root:"
                                + " without one the job can read — and move — files anywhere the"
                                + " process can reach"));
            }
        }
        if (poll.isRemote()) {
            if (poll.host() == null || poll.host().isBlank()) {
                findings.add(
                        new LintFinding(LintCodes.INVALID_JOB_TRIGGER, ERROR, source,
                                "Poll trigger source '" + kind + "' needs a host:"));
            } else {
                List<String> allowedHosts = new java.util.ArrayList<>();
                if (config
                        .navigate("tesseraql.connectors.poll.allowedHosts") instanceof List<?> h) {
                    h.forEach(value -> allowedHosts.add(String.valueOf(value)));
                }
                if (!io.tesseraql.yaml.http.HttpOutbound.hostAllowed(allowedHosts, poll.host())) {
                    findings.add(new LintFinding(POLL_HOST_NOT_ALLOWED, ERROR, source,
                            "Poll trigger targets host '" + poll.host() + "' which is not in"
                                    + " tesseraql.connectors.poll.allowedHosts (deny by default)"));
                }
            }
            if (poll.credential() != null && !poll.credential().isBlank()
                    && config.navigate(
                            "tesseraql.connectors.poll.credentials." + poll.credential()) == null) {
                findings.add(new LintFinding(POLL_UNDECLARED_CREDENTIAL, WARNING, source,
                        "Poll trigger references undeclared credential '" + poll.credential()
                                + "'"));
            }
            if ("sftp".equals(kind)
                    && config.navigate("tesseraql.connectors.poll.knownHostsFile") == null) {
                findings.add(new LintFinding(LintCodes.SFTP_HOST_KEY_UNVERIFIED, WARNING, source,
                        "SFTP poll source does not verify the server's SSH host key; set"
                                + " tesseraql.connectors.poll.knownHostsFile to pin it"));
            }
            // The FTPS counterpart, and an error rather than a warning: without a trust store
            // the client accepts any in-date certificate from any host, so the handshake proves
            // nothing about the peer. The runtime refuses to wire the job either way — lint is
            // the place the author finds out.
            if ("ftps".equals(kind)
                    && config.navigate("tesseraql.connectors.poll.trustStore") == null) {
                findings.add(new LintFinding(LintCodes.FTPS_SERVER_UNVERIFIED, ERROR, source,
                        "FTPS poll source does not verify the server certificate; set"
                                + " tesseraql.connectors.poll.trustStore (file:, password:) to"
                                + " pin the CA that signs it"));
            }
        }
        io.tesseraql.yaml.model.ImportSpec importSpec = job.definition().fileImport();
        io.tesseraql.yaml.model.Binding rowStep = job.definition().rowStep();
        if (importSpec == null || rowStep == null || rowStep.file() == null) {
            findings.add(new LintFinding(POLL_JOB_WITHOUT_IMPORT, ERROR, source,
                    "Poll-triggered job '"
                            + job.definition().id()
                            + "' needs an import: block saying how to parse the"
                            + " file, and a pipeline step saying what to write per row"));
        } else if (!Files.isRegularFile(
                job.source().getParent().resolve(rowStep.file()))) {
            findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                    "Referenced SQL file is missing: " + rowStep.file()));
        }
    }
}
