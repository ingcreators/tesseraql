package io.tesseraql.studio;

import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.studio.StudioService.JobSummary;
import io.tesseraql.studio.StudioService.PreviewResult;
import io.tesseraql.studio.StudioService.RenderResult;
import io.tesseraql.studio.StudioService.RouteSummary;
import io.tesseraql.studio.StudioService.ScaffoldFile;
import io.tesseraql.studio.StudioService.ScaffoldPreview;
import io.tesseraql.studio.StudioService.ScaffoldResult;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template-ready views over the Studio backend (design ch. 16, 47): pure mappings from the
 * {@link StudioService} records to plain maps and lists, with source links pre-encoded, served to
 * the bundled studio app through the {@code studio.*} service providers.
 */
public final class StudioViews {

    private static final String SOURCE_URL = "/_tesseraql/studio/ui/source?path=";

    private StudioViews() {
    }

    /** The explorer page model: the app name, edit mode, and its routes and jobs. */
    public static Map<String, Object> explorer(Explorer explorer) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appName", explorer.appName());
        model.put("readOnly", explorer.readOnly());
        model.put("editable", !explorer.readOnly());
        List<Map<String, Object>> routes = new ArrayList<>();
        for (RouteSummary route : explorer.routes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", route.id());
            row.put("method", route.method());
            row.put("path", route.path());
            row.put("recipe", route.recipe());
            row.put("source", route.source());
            row.put("sourceUrl", sourceUrl(route.source()));
            routes.add(row);
        }
        model.put("routes", routes);
        model.put("hasRoutes", !routes.isEmpty());
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobSummary job : explorer.jobs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", job.id());
            row.put("recipe", job.recipe());
            row.put("source", job.source());
            row.put("sourceUrl", sourceUrl(job.source()));
            jobs.add(row);
        }
        model.put("jobs", jobs);
        model.put("hasJobs", !jobs.isEmpty());
        return model;
    }

    /**
     * The source page model: the file path, its content ({@code content} is a draft when one
     * exists, otherwise the source), edit mode, and — for the editor — whether an unsaved draft is
     * being shown plus the on-disk {@code sourceContent} so the draft can be compared against it.
     *
     * <p>For template files ({@code .html}/{@code .tpl}) it also carries {@code isTemplate} and
     * {@code isHtmlTemplate} flags and the colocated {@code sampleModel} fixture (or empty), so the
     * editor can offer the rendered-preview-against-data panel (Studio backlog A1).
     */
    public static Map<String, Object> source(String path, String content, boolean readOnly,
            boolean hasDraft, String sourceContent, String sampleModel) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("path", path);
        model.put("content", content);
        model.put("readOnly", readOnly);
        model.put("editable", !readOnly);
        model.put("hasDraft", hasDraft);
        model.put("sourceContent", sourceContent);
        model.put("contentHtml", Highlighter.highlight(path, content));
        model.put("sourceContentHtml", Highlighter.highlight(path, sourceContent));
        boolean isTemplate = path != null && (path.endsWith(".html") || path.endsWith(".tpl"));
        boolean isRoute = isRouteYaml(path);
        boolean isJob = path != null && path.startsWith("batch/") && path.endsWith(".yml");
        model.put("isTemplate", isTemplate);
        model.put("isHtmlTemplate", path != null && path.endsWith(".html"));
        model.put("isRoute", isRoute);
        model.put("isJob", isJob);
        model.put("isRenderable", isTemplate || isRoute);
        // A route or a job carries declarative test cases the editor can run (Studio backlog A2).
        model.put("isTestable", isRoute || isJob);
        model.put("sampleModel", sampleModel == null ? "" : sampleModel);
        if (hasDraft) {
            model.put("diff", diffLines(path, sourceContent == null ? "" : sourceContent,
                    content == null ? "" : content));
        }
        return model;
    }

    /**
     * The live-validation fragment model for a {@link PreviewResult}: the raw outcome plus two
     * derived flags so the template carries no string-matching logic — {@code ok} for a clean
     * success and {@code needsData} for a template that parses but needs real route data to render.
     */
    public static Map<String, Object> preview(PreviewResult result) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("valid", result.valid());
        model.put("kind", result.kind());
        model.put("result", result.result());
        model.put("error", result.error());
        boolean needsData = result.valid() && result.result() != null
                && result.result().contains("needs route data");
        model.put("needsData", needsData);
        model.put("ok", result.valid() && !needsData);
        return model;
    }

    /**
     * The rendered-preview fragment model for a {@link RenderResult} (Studio backlog A1): the raw
     * outcome, the rendered {@code output} highlighted as {@code outputHtml} for the text surface,
     * an {@code isHtml} flag, and — for an HTML render — {@code previewDoc}, the output wrapped into
     * a standalone document linking the Hypermedia Components stylesheet so a sandboxed iframe shows
     * the actual styled result.
     */
    public static Map<String, Object> render(RenderResult result) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("ok", result.ok());
        model.put("kind", result.kind());
        model.put("error", result.error());
        boolean isHtml = "html".equals(result.kind());
        model.put("isHtml", isHtml);
        String output = result.output() == null ? "" : result.output();
        model.put("output", output);
        model.put("outputHtml",
                Highlighter.highlight(isHtml ? "output.html" : "output.txt", output));
        if (result.ok() && isHtml) {
            model.put("previewDoc", previewDoc(output));
        }
        return model;
    }

    private static final String SCAFFOLD_DISABLED = "Scaffolding is disabled "
            + "(set tesseraql.studio.scaffold.enabled and make Studio writable).";
    private static final String PREVIEW_URL = "/_tesseraql/studio/ui/scaffold/preview";

    /**
     * The scaffold page model (Studio backlog B3): the dev datasource's introspected tables, each
     * flagged {@code scaffoldable} (a base table with a single-column primary key the CRUD generator
     * supports). A {@code null} catalog — scaffolding disabled, or no datasource — yields an empty,
     * {@code enabled: false} model carrying an explanatory note.
     */
    public static Map<String, Object> scaffoldTables(CatalogSchema catalog, boolean enabled) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("enabled", enabled);
        model.put("previewUrl", PREVIEW_URL);
        List<Map<String, Object>> tables = new ArrayList<>();
        if (enabled && catalog != null) {
            for (CatalogSchema.Table table : catalog.tables()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", table.name());
                row.put("type", table.type());
                row.put("columnCount", table.columns().size());
                row.put("primaryKey", String.join(", ", table.primaryKey()));
                boolean scaffoldable = "TABLE".equalsIgnoreCase(table.type())
                        && table.primaryKey().size() == 1;
                row.put("scaffoldable", scaffoldable);
                tables.add(row);
            }
        }
        model.put("tables", tables);
        model.put("hasTables", !tables.isEmpty());
        model.put("note", enabled ? null : SCAFFOLD_DISABLED);
        return model;
    }

    /**
     * The scaffold-preview fragment model (Studio backlog B3): the generated CRUD files for one
     * table, each with its highlighted content and apply disposition ({@code status}), plus the
     * write/conflict counts a confirmation step shows. A {@code null} preview — scaffolding disabled
     * — yields an {@code enabled: false} model with a note.
     */
    public static Map<String, Object> scaffoldPreview(ScaffoldPreview preview) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (preview == null) {
            model.put("enabled", false);
            model.put("note", SCAFFOLD_DISABLED);
            model.put("files", List.of());
            return model;
        }
        model.put("enabled", true);
        model.put("table", preview.table());
        model.put("total", preview.total());
        model.put("writeCount", preview.writeCount());
        model.put("conflictCount", preview.conflictCount());
        model.put("hasConflicts", preview.conflictCount() > 0);
        List<Map<String, Object>> files = new ArrayList<>();
        for (ScaffoldFile file : preview.files()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", file.path());
            row.put("status", file.status());
            row.put("contentHtml", Highlighter.highlight(file.path(), file.content()));
            row.put("sourceUrl", sourceUrl(file.path()));
            files.add(row);
        }
        model.put("files", files);
        return model;
    }

    /**
     * The scaffold-apply fragment model (Studio backlog B3): the files written (each linked to the
     * source editor), left unchanged, and skipped as conflicts, with their counts and the
     * {@code blocked} flag, plus the {@code needsRestart} notice when new route files were written
     * (the hot reloader only swaps existing routes). A {@code null} result — scaffolding disabled —
     * yields an {@code enabled: false} model with a note.
     */
    public static Map<String, Object> scaffoldResult(ScaffoldResult result) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (result == null) {
            model.put("enabled", false);
            model.put("note", SCAFFOLD_DISABLED);
            model.put("written", List.of());
            model.put("skipped", List.of());
            return model;
        }
        model.put("enabled", true);
        model.put("table", result.table());
        List<Map<String, Object>> written = new ArrayList<>();
        for (String path : result.written()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path);
            row.put("sourceUrl", sourceUrl(path));
            written.add(row);
        }
        model.put("written", written);
        model.put("writtenCount", result.written().size());
        model.put("unchanged", result.unchanged());
        model.put("unchangedCount", result.unchanged().size());
        model.put("skipped", result.skipped());
        model.put("skippedCount", result.skipped().size());
        model.put("blocked", result.blocked());
        model.put("hasSkipped", !result.skipped().isEmpty());
        model.put("newRouteCount", result.newRoutes().size());
        model.put("needsRestart", !result.newRoutes().isEmpty());
        return model;
    }

    /** The Hypermedia Components stylesheets the shell links, reused to style the iframe preview. */
    private static final String PREVIEW_HEAD = "<meta charset=\"utf-8\">"
            + "<link rel=\"stylesheet\""
            + " href=\"/assets/vendor/hypermedia-components__core/dist/hc.min.css\">"
            + "<link rel=\"stylesheet\" href=\"/assets/_tesseraql/tesseraql.css\">";

    /**
     * Wraps rendered HTML for a sandboxed iframe {@code srcdoc}: a full-page render (one that brings
     * its own {@code <html>}, e.g. via the {@code tql/shell} layout) is shown verbatim, while a bare
     * fragment is wrapped in a minimal document linking the hc stylesheet so it is styled like the
     * real app. The dark theme matches the Studio shell.
     */
    private static String previewDoc(String html) {
        String lower = html.stripLeading().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
            return html;
        }
        return "<!DOCTYPE html><html lang=\"en\" data-theme=\"dark\"><head>" + PREVIEW_HEAD
                + "</head><body>" + html + "</body></html>";
    }

    private static final java.util.Set<String> HTTP_METHODS = java.util.Set.of("get", "post", "put",
            "patch", "delete", "head", "options");

    /** Whether the path is a web route document ({@code web/**}/{@code <method>.yml}) — renderable. */
    private static boolean isRouteYaml(String path) {
        if (path == null || !path.startsWith("web/") || !path.endsWith(".yml")) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        String stem = path.substring(slash + 1, path.length() - ".yml".length());
        return HTTP_METHODS.contains(stem);
    }

    private static String sourceUrl(String source) {
        return SOURCE_URL + URLEncoder.encode(source, StandardCharsets.UTF_8);
    }

    /**
     * Cap on either side's line count before the {@code O(n*m)} LCS is skipped (the compare panel
     * falls back to the saved source verbatim); Studio source files are far smaller in practice.
     */
    private static final int DIFF_MAX_LINES = 1500;

    /**
     * A unified line diff of {@code oldText} → {@code newText} as a template-ready line model for
     * the {@code hc-code data-mode="diff"} surface: each entry is a {@code state}
     * ({@code context}/{@code added}/{@code removed}), 1-based {@code oldNo}/{@code newNo} line
     * numbers (null on the side a line is absent from), and the line {@code text}. Unchanged lines
     * stay {@code context} (computed from an LCS). Returns {@code null} when either side exceeds
     * {@link #DIFF_MAX_LINES}, so the caller falls back to a plain source view.
     */
    private static List<Map<String, Object>> diffLines(String path, String oldText,
            String newText) {
        String[] a = oldText.isEmpty() ? new String[0] : oldText.split("\n", -1);
        String[] b = newText.isEmpty() ? new String[0] : newText.split("\n", -1);
        if (a.length > DIFF_MAX_LINES || b.length > DIFF_MAX_LINES) {
            return null;
        }
        int n = a.length;
        int m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                lines.add(diffLine(path, "context", i + 1, j + 1, a[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                lines.add(diffLine(path, "removed", i + 1, null, a[i]));
                i++;
            } else {
                lines.add(diffLine(path, "added", null, j + 1, b[j]));
                j++;
            }
        }
        while (i < n) {
            lines.add(diffLine(path, "removed", i + 1, null, a[i++]));
        }
        while (j < m) {
            lines.add(diffLine(path, "added", null, j + 1, b[j++]));
        }
        return lines;
    }

    private static Map<String, Object> diffLine(String path, String state, Integer oldNo,
            Integer newNo, String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("state", state);
        line.put("oldNo", oldNo);
        line.put("newNo", newNo);
        line.put("text", text);
        line.put("html", Highlighter.highlight(path, text));
        return line;
    }
}
