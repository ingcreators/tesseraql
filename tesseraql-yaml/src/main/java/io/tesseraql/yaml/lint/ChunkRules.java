package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import java.nio.file.Path;
import java.util.List;

/**
 * A batch step's {@code chunk:} block and the spooled result it references.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ChunkRules {

    private static final String CHUNK_READER_WITHOUT_ORDER = "TQL-BATCH-4207";

    private static final String CHUNK_READER_WITHOUT_CHECKPOINT_BIND = "TQL-BATCH-4208";

    private ChunkRules() {
    }

    /**
     * A {@code spool:} names an earlier step's spool, and only a {@code mode: query-spool} step
     * publishes one. A reference to a later step, to a step that never spooled, or to nothing at
     * all fails at execution with the load half already scheduled — which on a batch estate is
     * the middle of the night.
     */
    static void lintSpoolReference(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String reference, String source,
            List<LintFinding> findings) {
        String[] path = reference.split("\\.");
        if (path.length < 2 || !"steps".equals(path[0])) {
            findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                    "Step '" + step.id()
                            + "': reader spool: '" + reference
                            + "' must name an earlier step's spool"
                            + " (steps.<id>.spool)"));
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
            if (earlier.sql() == null
                    || !"query-spool".equals(earlier.sql().effectiveMode())) {
                findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                        "Step '"
                                + step.id() + "': step '" + referenced
                                + "' publishes no spool - only a"
                                + " mode: query-spool step does"));
            }
            return;
        }
        findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                "Step '" + step.id()
                        + "': reader spool: names '" + referenced
                        + "', which is not an earlier step"));
    }

    /**
     * Statically checks a chunk step (docs/batch-platform.md track C). The restart contract
     * lives in the reader's SQL, so the reader is read here: without an {@code order by} the
     * resume point is undefined ({@code TQL-BATCH-4207}, an error), and a reader that never
     * binds {@code chunk.after} reprocesses from the top on every restart — legal for an
     * idempotent writer, worth saying out loud ({@code TQL-BATCH-4208}, a warning).
     */
    static void lintChunk(LintContext context, io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ChunkSpec chunk = step.chunk();
        // A reader names exactly one input: its own SQL, or an earlier step's spool
        // (docs/unified-sources.md decision 19). Both stream, so the choice is where the rows
        // come from, not how much memory the step takes.
        boolean readsSql = chunk.reader() != null && chunk.reader().file() != null
                && !chunk.reader().file().isBlank();
        boolean readsSpool = chunk.reader() != null && chunk.reader().isSpool();
        if (readsSql == readsSpool) {
            findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                    "Step '" + step.id()
                            + "': chunk needs exactly one reader - sql: { file: … }, or spool: naming an"
                            + " earlier step's spool"));
            return;
        }
        if (chunk.writer() == null || chunk.writer().file() == null
                || chunk.writer().file().isBlank()) {
            findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                    "Step '" + step.id()
                            + "': chunk needs writer: { sql: { file: … } }"));
            return;
        }
        if (readsSpool) {
            lintSpoolReference(job, step, chunk.reader().spool(), source, findings);
        }
        if (chunk.commitEvery() != null && chunk.commitEvery() < 1) {
            findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                    "Step '" + step.id()
                            + "': chunk commitEvery must be at least 1 (was " + chunk.commitEvery()
                            + ")"));
        }
        if (chunk.onError() != null && !List.of("fail", "skip").contains(chunk.onError())) {
            findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                    "Step '" + step.id()
                            + "': chunk onError must be fail or skip (was '" + chunk.onError()
                            + "')"));
        }
        if (chunk.skipLimit() != null && chunk.skipLimit() < 0) {
            findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                    "Step '" + step.id()
                            + "': chunk skipLimit must not be negative (was " + chunk.skipLimit()
                            + ")"));
        }
        // Structural decision 6 (docs/sql-execution-shapes.md): skip semantics are per-row by
        // definition, and a JDBC batch cannot attribute a member failure to one row on every
        // driver — the two declarations contradict each other.
        if (chunk.batches() && "skip".equals(chunk.onError())) {
            findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                    "Step '" + step.id()
                            + "': chunk batch: true requires the default onError: fail - a"
                            + " batched writer cannot attribute a failure to one row, so it"
                            + " cannot skip; drop batch:, or drop onError: skip"));
        }
        chunk.enrich().forEach((name, enrich) -> {
            if (enrich.sql() == null || enrich.sql().file() == null
                    || enrich.sql().file().isBlank()) {
                return;
            }
            Path file = job.source().getParent().resolve(enrich.sql().file()).normalize();
            if (!java.nio.file.Files.isRegularFile(file)) {
                findings.add(new LintFinding(LintCodes.STEP_REFERENCE_UNRESOLVED, ERROR, source,
                        "Step '"
                                + step.id() + "': chunk enrich '" + name
                                + "' references a missing SQL"
                                + " file: " + enrich.sql().file()));
            }
        });
        if (readsSpool) {
            // The restart contract of a spooled reader is not in any SQL this document owns:
            // the spool is a snapshot, replayed in the order it was written, and the checkpoint
            // is a position in that file. There is nothing to read for an order by.
            return;
        }
        Path readerPath = job.source().getParent().resolve(chunk.reader().file()).normalize();
        if (!java.nio.file.Files.isRegularFile(readerPath)) {
            return; // the missing file is its own finding where SQL files are checked
        }
        String readerSql = context.content(readerPath);
        if (readerSql == null) {
            return;
        }
        String lower = readerSql.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("order by")) {
            findings.add(new LintFinding(CHUNK_READER_WITHOUT_ORDER, ERROR, source,
                    "Step '" + step.id()
                            + "': the chunk reader has no order by — without a deterministic order"
                            + " the checkpoint cannot say where to resume"));
        }
        if (!readerSql.contains("chunk.after")) {
            findings.add(new LintFinding(CHUNK_READER_WITHOUT_CHECKPOINT_BIND, WARNING, source,
                    "Step '"
                            + step.id() + "': the chunk reader never binds chunk.after — a restart"
                            + " reprocesses from the top, which is only safe for an idempotent"
                            + " writer"));
        }
    }
}
