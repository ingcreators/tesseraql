package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * The per-step unit-of-work checks a batch pipeline step runs: one binding arm,
 * an enrichment that folds into rows, an http mode, a step datasource.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class StepRules {

    private StepRules() {
    }

    /**
     * A step's own {@code enrich:} folds references into the rows the step read, so the step has
     * to have rows: {@code mode: query} or an {@code http:} call. A write, a sequence allocation
     * and a {@code query-spool} extract hold none — spooling is the declaration that the rows
     * were never held — and a chunk step folds its references on the reader, per window.
     *
     * <p>Each reference's SQL file is checked here too, the way a chunk's are: a missing lookup
     * discovered at 3am is a build error that was available all along.
     */
    static void lintStepEnrich(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String source, List<LintFinding> findings) {
        if (step.sql() == null || step.sql().enrich().isEmpty()) {
            return;
        }
        if (!holdsRows(step)) {
            findings.add(new LintFinding(LintCodes.STEP_WORK_SHAPE, ERROR, source,
                    "Step '" + step.id()
                            + "' declares enrich: but holds no rows - only a step that reads (mode:"
                            + " query, or an http: call) has rows to fold a reference into; a chunk"
                            + " step declares its enrich: on the reader"));
            return;
        }
        step.sql().enrich().forEach((name, enrich) -> {
            lintSiblingReference(job, step, name, enrich, source, findings);
            if (enrich.sql() == null || enrich.sql().file() == null
                    || enrich.sql().file().isBlank()) {
                return;
            }
            Path file = job.source().getParent().resolve(enrich.sql().file()).normalize();
            if (!java.nio.file.Files.isRegularFile(file)) {
                findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                        "Step '"
                                + step.id() + "': enrich '" + name
                                + "' references a missing SQL file: "
                                + enrich.sql().file()));
            }
        });
    }

    /**
     * Whether a step publishes {@code rows} — a {@code sql:} read ({@code mode: query}) or an
     * {@code http:} call that holds its rows rather than spooling them. The one answer for
     * "may this step fold a reference in" and "may a reference name this step".
     */
    static boolean holdsRows(io.tesseraql.yaml.model.PipelineStep step) {
        if (step.sql() == null) {
            return false;
        }
        String mode = step.sql().effectiveMode();
        return step.sql().declaresHttp()
                ? !"query-spool".equals(mode)
                : step.sql().isSql() && "query".equals(mode);
    }

    /**
     * A step's {@code enrich:} entry that composes a sibling ({@code source:}) names it by its
     * context path, {@code steps.<id>} (docs/jobs.md "Enriching a step's rows") — and the
     * runtime resolves that path only against the steps already run, requiring a result with
     * {@code rows}. So the reference must name an <em>earlier</em> step that holds rows
     * ({@code TQL-YAML-1046}, the code a route's sibling reference gets from
     * {@link EnrichRules}); a route-style bare name, a later step, a write or a spool would
     * each fail at fire time with the runtime's no-sibling refusal, having passed lint in
     * silence.
     */
    static void lintSiblingReference(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String name,
            io.tesseraql.yaml.model.EnrichSpec enrich, String source, List<LintFinding> findings) {
        if (!enrich.composesSource()) {
            return;
        }
        String[] path = enrich.source().split("\\.");
        if (path.length != 2 || !"steps".equals(path[0]) || path[1].isBlank()) {
            findings.add(new LintFinding(EnrichRules.INVALID_ENRICH_REFERENCE, ERROR, source,
                    "Step '" + step.id() + "': enrich '" + name + "': source: '"
                            + enrich.source() + "' must name an earlier step's result"
                            + " (steps.<id>) - a job's results sit under steps"));
            return;
        }
        String referenced = path[1];
        for (io.tesseraql.yaml.model.PipelineStep earlier : job.definition().pipeline()) {
            if (earlier.id().equals(step.id())) {
                break;
            }
            if (!earlier.id().equals(referenced)) {
                continue;
            }
            if (!holdsRows(earlier)) {
                findings.add(new LintFinding(EnrichRules.INVALID_ENRICH_REFERENCE, ERROR, source,
                        "Step '" + step.id() + "': enrich '" + name + "': step '" + referenced
                                + "' holds no rows - only a step that reads (mode: query, or an"
                                + " http: call) publishes a result a reference can compose"));
            }
            return;
        }
        findings.add(new LintFinding(EnrichRules.INVALID_ENRICH_REFERENCE, ERROR, source,
                "Step '" + step.id() + "': enrich '" + name + "': source: names '" + referenced
                        + "', which is not an earlier step"));
    }

    /**
     * A stored call is command-step vocabulary for now (docs/sql-execution-shapes.md structural
     * decision 7, the job side recorded as deferred): a job {@code sql:} step declaring
     * {@code mode: call} or {@code out:} would otherwise run as a plain update with its OUT
     * bind sites bound as null values — the author's declaration silently meaning something
     * else.
     */
    static void lintSqlCall(io.tesseraql.yaml.model.PipelineStep step, String source,
            List<LintFinding> findings) {
        if (step.sql() == null || !step.sql().isSql()) {
            return;
        }
        if ("call".equals(step.sql().effectiveMode()) || !step.sql().out().isEmpty()) {
            findings.add(new LintFinding(LintCodes.STEP_WORK_SHAPE, ERROR, source,
                    "Step '" + step.id() + "': mode: call and out: are command-step vocabulary -"
                            + " run the stored call from a command route"
                            + " (docs/sql-execution-shapes.md records the job-side direction)"));
        }
    }

    /**
     * An arm's {@code mode:} values are the mechanism's (docs/unified-sources.md decision 19a).
     * A call reads: it either holds its rows ({@code query}) or spools them
     * ({@code query-spool}). {@code update} or {@code query-one} on an {@code http:} arm is a
     * SQL mode written on a call — accepted silently it would run as a plain query, so the
     * author's mistaken expectation about what the step publishes survives to production.
     */
    static void lintHttpMode(io.tesseraql.yaml.model.PipelineStep step, String source,
            List<LintFinding> findings) {
        String mode = step.sql().http().mode();
        if (mode == null || mode.isBlank() || "query".equals(mode)
                || "query-spool".equals(mode)) {
            return;
        }
        findings.add(new LintFinding(LintCodes.STEP_WORK_SHAPE, ERROR, source, "Step '" + step.id()
                + "': http: mode '" + mode + "' is not a mode a call has - an outbound call"
                + " reads, so it is query (the rows are held) or query-spool (they are streamed"
                + " to a spool a chunk: step reads)"));
    }

    /**
     * A batch step may run its <em>read</em> on a connector other than the job's
     * (docs/unified-sources.md decision 19): each batch step owns its transaction, so the
     * override splits nothing — which is what makes "extract from one database, load into
     * another" expressible at all. A <em>write</em> may not: that would be a second transaction
     * the executor does not own, the stance {@code TQL-YAML-1037} has always enforced.
     */
    static void lintStepDatasource(AppConfig config,
            io.tesseraql.yaml.model.PipelineStep step, String source, List<LintFinding> findings) {
        String declared = step.sql() == null ? null : step.sql().datasource();
        if (declared == null || declared.isBlank()) {
            return;
        }
        String mode = step.sql().effectiveMode();
        if (!"query".equals(mode) && !"query-spool".equals(mode)) {
            findings.add(new LintFinding(LintCodes.DATASOURCE_SPLITS_TRANSACTION, ERROR, source,
                    "Step '" + step.id()
                            + "': only a read step may declare datasource: - a write on another"
                            + " connector would be a second transaction the job does not own"));
            return;
        }
        if (!"main".equals(declared)
                && config.navigate("tesseraql.datasources." + declared) == null) {
            findings.add(new LintFinding(LintCodes.UNDECLARED_DATASOURCE, ERROR, source,
                    "Step '" + step.id()
                            + "': datasource '" + declared
                            + "' is not declared under tesseraql.datasources"));
        }
    }

    /**
     * Whether a step's binding carries only its {@code when:} guard — a guard is step control,
     * not an arm, so a step spelling one without a mechanism has declared no work.
     */
    static boolean isGuardOnly(io.tesseraql.yaml.model.Binding binding) {
        return !binding.isSql() && !binding.isContract() && !binding.isService()
                && !binding.isSequence() && !binding.declaresHttp();
    }
}
