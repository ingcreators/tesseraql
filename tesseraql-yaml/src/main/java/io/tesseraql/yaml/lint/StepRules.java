package io.tesseraql.yaml.lint;

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
        String mode = step.sql().effectiveMode();
        boolean reads = step.sql().declaresHttp()
                ? !"query-spool".equals(mode)
                : step.sql().isSql() && "query".equals(mode);
        if (!reads) {
            findings.add(new LintFinding("TQL-FIELD-2004", "error", source, "Step '" + step.id()
                    + "' declares enrich: but holds no rows - only a step that reads (mode:"
                    + " query, or an http: call) has rows to fold a reference into; a chunk"
                    + " step declares its enrich: on the reader"));
            return;
        }
        step.sql().enrich().forEach((name, enrich) -> {
            if (enrich.sql() == null || enrich.sql().file() == null
                    || enrich.sql().file().isBlank()) {
                return;
            }
            Path file = job.source().getParent().resolve(enrich.sql().file()).normalize();
            if (!java.nio.file.Files.isRegularFile(file)) {
                findings.add(new LintFinding("TQL-BATCH-4206", "error", source, "Step '"
                        + step.id() + "': enrich '" + name + "' references a missing SQL file: "
                        + enrich.sql().file()));
            }
        });
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
        findings.add(new LintFinding("TQL-FIELD-2004", "error", source, "Step '" + step.id()
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
            findings.add(new LintFinding("TQL-YAML-1037", "error", source, "Step '" + step.id()
                    + "': only a read step may declare datasource: - a write on another"
                    + " connector would be a second transaction the job does not own"));
            return;
        }
        if (!"main".equals(declared)
                && config.navigate("tesseraql.datasources." + declared) == null) {
            findings.add(new LintFinding("TQL-YAML-1035", "error", source, "Step '" + step.id()
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
