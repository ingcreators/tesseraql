package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.util.List;

/**
 * The export block wherever it rides — a route's {@code export:} and a batch
 * step's — its sources, group ordering and row cap.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ExportRules {

    private static final String INCOMPLETE_EXPORT_STEP = "TQL-YAML-1041";

    private static final String EXPORT_GROUPS_WITHOUT_TEMPLATE = "TQL-LD-5312";

    private static final String EXPORT_GROUPS_WITHOUT_ORDER = "TQL-LD-5311";

    private static final String EXPORT_WITHOUT_MAX_ROWS = "TQL-LD-5310";

    private ExportRules() {
    }

    /**
     * Statically checks an export step (docs/analytics-experience.md track 3): the extraction
     * query is required, and {@code after.timing: download} stays route vocabulary — a job-produced file's download is an ops action, not
     * a business signal, so the only follow-up a step supports is the extraction-transaction
     * one ({@code TQL-YAML-1041}).
     */
    static void lintExportStep(LintContext context, io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.PipelineStep step, String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ExportSpec export = step.export();
        // The rows come from the step's own arm, never from inside export: — an output block
        // says how to write, not what to read (docs/unified-sources.md, decision 7).
        if (step.sql() == null || step.sql().file() == null || step.sql().file().isBlank()) {
            findings.add(new LintFinding(INCOMPLETE_EXPORT_STEP, ERROR, source, "Step '" + step.id()
                    + "': an export step needs the rows to write — declare the step's own"
                    + " sql: { file: … }"));
            return;
        }
        if (export.format() == null || export.format().isBlank()) {
            findings.add(new LintFinding(INCOMPLETE_EXPORT_STEP, ERROR, source, "Step '" + step.id()
                    + "': export needs format: (csv, excel, or pdf)"));
        }
        if (export.after() != null && io.tesseraql.core.files.FileTransferService.AFTER_DOWNLOAD
                .equals(export.after().effectiveTiming())) {
            findings.add(new LintFinding(INCOMPLETE_EXPORT_STEP, ERROR, source, "Step '" + step.id()
                    + "': after.timing: download is route vocabulary — an export step supports"
                    + " timing: extract only"));
        }
        if ("pdf".equals(export.format())) {
            if (export.sheet() != null || export.startCell() != null) {
                findings.add(new LintFinding(LintCodes.INVALID_TRIGGER_OR_EXPORT_OPTION, ERROR,
                        source,
                        "pdf export: sheet:/startCell: are workbook options - a pdf lays out"
                                + " through its template, not cell placement"));
            }
            if (export.template() != null && !export.template().endsWith(".html")) {
                findings.add(
                        new LintFinding(LintCodes.MISSING_EXPORT_TEMPLATE_OR_IMPORT, ERROR, source,
                                "pdf export template '" + export.template()
                                        + "' must be an .html file (it renders through the template"
                                        + " engine before PDF conversion)"));
            }
        }
        if (!"pdf".equals(export.format()) && export.startCell() != null
                && export.template() == null) {
            findings.add(new LintFinding(INCOMPLETE_EXPORT_STEP, ERROR, source, "Step '" + step.id()
                    + "': startCell: places data into a template, but none is declared - add"
                    + " template:, or drop startCell: for a plain grid"));
        }
        if (export.template() != null && (!"pdf".equals(export.format())
                || export.template().endsWith(".html"))
                && !Files.isRegularFile(
                        job.source().getParent().resolve(export.template()))) {
            findings.add(new LintFinding(LintCodes.MISSING_EXPORT_TEMPLATE_OR_IMPORT, ERROR, source,
                    "Step '" + step.id()
                            + "': export references a missing template: " + export.template()));
        }
        lintExportRowCap(export, "Step '" + step.id() + "': ", source, findings);
        lintExportSources(context, export, java.util.Map.of(),
                step.sql() == null || step.sql().file() == null
                        ? null
                        : job.source().getParent().resolve(step.sql().file()),
                "Step '" + step.id() + "': ", source, findings);
    }

    /**
     * Statically checks a route's {@code export:} block (docs/export-pipeline.md, decision 4).
     *
     * <p>The workbook mode is inferred from the declaration — a template with {@code startCell:}
     * is placement, a template alone is a jxls report, neither is a grid — so a declaration that
     * cannot mean what it says silently produces a different document. A missing template file
     * used to fall through to a plain grid on routes (job steps have been checked all along), and
     * {@code startCell:} without a template named a mode that does not exist.
     *
     * <p>{@code format: pdf} is a print format on top of that: the workbook-only options do not
     * apply, and its template renders through the standard template engine, so it must be
     * {@code .html}.
     */
    static void lintRouteExport(LintContext context, RouteFile route, RouteDefinition definition,
            String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        if (spec == null) {
            return;
        }
        boolean pdf = "pdf".equals(spec.format());
        if (pdf && (spec.sheet() != null || spec.startCell() != null)) {
            findings.add(new LintFinding(LintCodes.INVALID_TRIGGER_OR_EXPORT_OPTION, ERROR, source,
                    "pdf export: sheet:/startCell: are workbook options - a pdf lays out"
                            + " through its template, not cell placement"));
        }
        if (!pdf && spec.startCell() != null && spec.template() == null) {
            findings.add(new LintFinding(LintCodes.INVALID_TRIGGER_OR_EXPORT_OPTION, ERROR, source,
                    "export: startCell: places data into a template, but none is declared -"
                            + " add template:, or drop startCell: for a plain grid"));
        }
        if (spec.template() == null) {
            return;
        }
        if (pdf && !spec.template().endsWith(".html")) {
            findings.add(new LintFinding(LintCodes.MISSING_EXPORT_TEMPLATE_OR_IMPORT, ERROR, source,
                    "pdf export template '" + spec.template()
                            + "' must be an .html file (it renders through the template"
                            + " engine before PDF conversion)"));
            return;
        }
        if (!Files.isRegularFile(route.source().getParent().resolve(spec.template()))) {
            findings.add(new LintFinding(LintCodes.MISSING_EXPORT_TEMPLATE_OR_IMPORT, ERROR, source,
                    "export references a missing template: " + spec.template()));
        }
    }

    /**
     * An export's other declared sources (docs/export-pipeline.md, decision 2) need somewhere to
     * go. CSV and the Excel grid write rows and nothing else, so a named query or an HTTP source
     * declared beside them runs to be discarded — a cost with no reader.
     *
     * <p>{@code onError: empty} is refused outright on an export. On a page, degrading to zero
     * rows leaves a gap a human sees; in a document that is archived, mailed and filed, it
     * produces something that looks complete and is not.
     */
    static void lintExportSources(LintContext context, io.tesseraql.yaml.model.ExportSpec spec,
            java.util.Map<String, io.tesseraql.yaml.model.Binding> sources,
            java.nio.file.Path extractionSql, String label, String source,
            List<LintFinding> findings) {
        if (spec == null) {
            return;
        }
        java.util.Map<String, io.tesseraql.yaml.model.HttpSourceSpec> httpSources = new java.util.LinkedHashMap<>();
        java.util.Map<String, io.tesseraql.yaml.model.Binding> composed = new java.util.LinkedHashMap<>(
                sources);
        composed.remove(RouteDefinition.MAIN);
        composed.forEach((name, binding) -> {
            if (binding != null && binding.isHttp()) {
                httpSources.put(name, binding.http());
            }
        });
        httpSources.forEach((name, http) -> {
            if (http != null && http.degradesToEmpty()) {
                findings.add(new LintFinding(LintCodes.MISSING_EXPORT_TEMPLATE_OR_IMPORT, ERROR,
                        source, label
                                + "http: source '" + name
                                + "' declares onError: empty on an export -"
                                + " a document that is archived and mailed would look complete with a"
                                + " section missing, so an export whose source failed should fail"));
            }
        });
        if (spec.splitBy() != null && !spec.splitBy().isBlank()) {
            String filename = spec.filename();
            if (filename == null || !filename.contains("{key}")) {
                findings.add(new LintFinding(LintCodes.MISSING_EXPORT_TEMPLATE_OR_IMPORT, ERROR,
                        source, label
                                + "splitBy: writes one document per group, so filename: must carry {key}"
                                + " - otherwise every group would be written to the same name and only"
                                + " the last would survive"));
            }
            lintGroupOrdering(context, spec, extractionSql, spec.splitBy(), label, source,
                    findings);
        }
        if (spec.groupBy() != null && !spec.groupBy().isBlank()) {
            if (spec.template() == null) {
                findings.add(new LintFinding(EXPORT_GROUPS_WITHOUT_TEMPLATE, WARNING, source, label
                        + "export declares groupBy: but no template: - a " + spec.format()
                        + " export writes rows and nothing else, so the groups have no reader"));
            }
            lintGroupOrdering(context, spec, extractionSql, spec.groupBy(), label, source,
                    findings);
        }
        boolean templated = spec.template() != null;
        if (templated || composed.isEmpty()) {
            return;
        }
        findings.add(new LintFinding(EXPORT_GROUPS_WITHOUT_TEMPLATE, WARNING, source, label
                + "the document declares sources beside main: but the export has no template: -"
                + " a " + spec.format() + " export writes the main rows and nothing else, so"
                + " they would run to be discarded"));
    }

    /** The file an export extracts from: the document's {@code main} source, on every recipe. */
    static java.nio.file.Path extractionSqlFile(RouteFile route,
            RouteDefinition definition) {
        // One home: the rows an export writes are the document's main source, on every recipe
        // (docs/unified-sources.md, decision 7).
        if (definition.main() != null && definition.main().file() != null) {
            return route.source().getParent().resolve(definition.main().file());
        }
        return null;
    }

    /**
     * A grouped export detects its boundaries on a pass through the rows, so unordered rows would
     * write one group as several (docs/export-pipeline.md, decision 3). The runtime refuses them
     * with {@code TQL-LD-2851}; this says so at build time, from the text of the 2-way SQL, in the
     * shape the mail lints already use — a heuristic, hence a warning.
     */
    static void lintGroupOrdering(LintContext context, io.tesseraql.yaml.model.ExportSpec spec,
            java.nio.file.Path sql, String column, String label, String source,
            List<LintFinding> findings) {
        if (sql == null || !Files.isRegularFile(sql)) {
            return;
        }
        String content = context.content(sql);
        if (content == null) {
            return;
        }
        String text = content.toLowerCase(java.util.Locale.ROOT);
        int orderBy = text.lastIndexOf("order by");
        if (orderBy >= 0
                && text.substring(orderBy).contains(column.toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        findings.add(new LintFinding(EXPORT_GROUPS_WITHOUT_ORDER, WARNING, source, label
                + "export groups by '" + column + "' but its query has no order by"
                + " naming that column - the runtime detects group boundaries on a single pass,"
                + " so unordered rows fail rather than writing one group as several"));
    }

    /**
     * An export through a format that holds every row before it writes runs under a ceiling
     * (docs/export-pipeline.md, decision 7), and an author who has not chosen one is the case
     * worth naming: until that decision, nothing at all stood between such an export and the heap.
     *
     * <p>The runtime authority is {@code FileCodec.streams(spec)}, which the linter cannot ask —
     * the optional codec modules are not on its classpath. This reads the declaration instead,
     * which answers for the formats the framework ships; anything else can say {@code maxRows: -1}
     * to state that it streams.
     */
    static void lintExportRowCap(io.tesseraql.yaml.model.ExportSpec spec, String label,
            String source, List<LintFinding> findings) {
        if (spec == null || spec.maxRows() != null) {
            return;
        }
        boolean buffers = "pdf".equals(spec.format())
                || ("excel".equals(spec.format()) && spec.template() != null);
        if (!buffers) {
            return;
        }
        findings.add(new LintFinding(EXPORT_WITHOUT_MAX_ROWS, WARNING, source, label
                + "export holds every row before it writes (" + spec.format()
                + (spec.template() == null ? "" : " through a template")
                + ") and declares no maxRows:, so it runs under the app-wide default - declare"
                + " export.maxRows: for the number this document can actually carry"));
    }
}
