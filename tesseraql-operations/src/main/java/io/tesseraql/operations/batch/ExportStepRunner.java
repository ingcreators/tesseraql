package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.yaml.model.PipelineStep;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** The {@code export:} step kind: the route recipes' export vocabulary, run by a job. */
final class ExportStepRunner {

    private ExportStepRunner() {
    }

    /**
     * Runs an export step (docs/analytics-experience.md track 3): the route recipes' export
     * vocabulary on the job's datasource. The extraction SQL renders exactly like a
     * {@code sql:} step's — dialect variant beside the file, ambient {@code batch.*} binds,
     * file placeholders against the job's datasource — and the transfer service executes it
     * synchronously through the codec into the spool, recording the same execution + transfer
     * rows an HTTP {@code file-export} records. The step publishes {@code transferId},
     * {@code rows}, and {@code filename} to the step context; {@code after:} runs in the
     * extraction transaction (the only timing a step supports).
     */
    static Map<String, Object> run(StepContext context) {
        PipelineStep step = context.step();
        if (context.fileTransfers() == null) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "' declares export: but no file-transfer"
                            + " service is wired")
                    .build();
        }
        io.tesseraql.yaml.model.ExportSpec export = step.export();
        io.tesseraql.core.sql.FilePathResolver filePathResolver = context.filePathResolver();
        BoundSql query = context.renderStepSql(step.sql(), context.dataSource(), filePathResolver);
        BoundSql afterExtract = export.after() == null || export.after().sql() == null
                ? null
                : context.renderStepSql(
                        io.tesseraql.yaml.model.Binding.sql(export.after().sql()),
                        context.dataSource(), filePathResolver);
        Path template = export.template() == null
                ? null
                : context.jobFile().source().getParent().resolve(export.template()).normalize();
        // A job has no request to resolve formatting from, so locale:/timezone: are literals.
        io.tesseraql.core.files.FileWriteSpec writeSpec = export
                .toWriteSpec(template, context.appHome())
                .withFormatting(export.locale(), export.timezone());
        io.tesseraql.core.files.FileTransferService.InlineResult result = context.fileTransfers()
                .exportInline(new io.tesseraql.core.files.FileTransferService.InlineExport(
                        context.jobFile().definition().id() + "#" + step.id(), context.appName(),
                        export.format(), writeSpec,
                        context.interpolate(export.filename()), query, afterExtract,
                        declaredExportRowCap(context, export),
                        // No per-step export queries: a step has one arm and it is the rows; a
                        // template's other data belongs to a job that declares it, which no
                        // pipeline step does yet (docs/unified-sources.md, decision 7).
                        Map.of()),
                        context.dataSource());
        Map<String, Object> stepResult = new LinkedHashMap<>();
        stepResult.put("affectedRows", (int) result.rows());
        stepResult.put("rows", result.rows());
        stepResult.put("transferId", result.transferId());
        stepResult.put("filename", result.filename());
        return stepResult;
    }

    /** The ceiling a step's export declares; whether it applies is the codec's answer. */
    private static io.tesseraql.core.files.ExportRowCap declaredExportRowCap(StepContext context,
            io.tesseraql.yaml.model.ExportSpec export) {
        return new io.tesseraql.core.files.ExportRowCap(
                export.maxRows() != null ? export.maxRows() : context.maxRows(),
                export.onOverflow() != null ? export.onOverflow() : context.onOverflow(),
                export.format());
    }
}
